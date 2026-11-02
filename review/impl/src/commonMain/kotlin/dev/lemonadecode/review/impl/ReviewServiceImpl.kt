package dev.lemonadecode.review.impl

import dev.lemonadecode.data.git.GitBlob
import dev.lemonadecode.data.git.GitDataSource
import dev.lemonadecode.data.git.GitError
import dev.lemonadecode.domain.review.CommentId
import dev.lemonadecode.domain.review.CommentStatus
import dev.lemonadecode.domain.review.DiffState
import dev.lemonadecode.domain.review.FileChange
import dev.lemonadecode.domain.review.FileContent
import dev.lemonadecode.domain.review.FileVersion
import dev.lemonadecode.domain.review.NewComment
import dev.lemonadecode.domain.review.Review
import dev.lemonadecode.domain.review.ReviewComment
import dev.lemonadecode.domain.session.Session
import dev.lemonadecode.domain.session.SessionId
import dev.lemonadecode.domain.session.SessionOrigin
import dev.lemonadecode.domain.session.SubSessionLocation
import dev.lemonadecode.platform.core.LemonadeCodeResult
import dev.lemonadecode.platform.core.getOrElse
import dev.lemonadecode.platform.core.getOrNull
import dev.lemonadecode.platform.core.map
import dev.lemonadecode.platform.core.mapError
import dev.lemonadecode.platform.di.AppScope
import dev.lemonadecode.review.api.ReviewError
import dev.lemonadecode.review.api.ReviewService
import dev.lemonadecode.sessions.api.SessionService
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Comments keyed by the git checkout a session works in; diffs keyed by the checkout and the branch
 * they compare with, which each session picks for itself.
 */
@OptIn(ExperimentalAtomicApi::class)
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class ReviewServiceImpl(
  private val sessions: SessionService,
  private val loadDiff: LoadDiffUseCase,
  private val git: GitDataSource,
  private val scope: CoroutineScope,
  private val web: WebImageSource,
) : ReviewService {
  private val loaded = MutableStateFlow(Loaded())
  private val comments = MutableStateFlow<Map<String, List<ReviewComment>>>(emptyMap())

  /** The branch each session's diff compares with, where one was picked. */
  private val chosenBases = MutableStateFlow<Map<SessionId, String>>(emptyMap())
  private val refreshLocks = mutableMapOf<DiffKey, Mutex>()
  private val refreshLocksGuard = Mutex()
  private val nextCommentId = AtomicLong(0)
  private val webImages = WebImageCache()

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun review(sessionId: SessionId): Flow<Review> = combine(sessions.sessions, chosenBases) { list, chosen -> target(sessionId, list, chosen) }
    .distinctUntilChanged()
    .flatMapLatest { target ->
      if (target == null) {
        flowOf(Review(DiffState.NotARepository, emptyList()))
      } else {
        combine(loaded, comments) { git, allComments ->
          val diff = git.diffs[target.key] ?: DiffState.Loading
          val refs = git.branches[target.key.root]
          Review(
            diff = diff,
            comments = allComments[target.key.root].orEmpty(),
            base = diff.baseRef() ?: target.key.base ?: refs?.default,
            bases = baseOptions(refs?.default, target.parentBranch, refs?.all.orEmpty()),
          )
        }
          .distinctUntilChanged()
          .onStart { if (loaded.value.diffs[target.key] == null) scope.launch { refresh(target.key) } }
      }
    }

  override suspend fun refresh(sessionId: SessionId) {
    target(sessionId)?.let { refresh(it.key) }
  }

  override suspend fun compareWith(sessionId: SessionId, branch: String) {
    chosenBases.update { it + (sessionId to branch) }
    refresh(sessionId)
  }

  override fun addComment(sessionId: SessionId, comment: NewComment): CommentId? {
    val key = session(sessionId)?.repositoryRoot ?: return null
    val id = CommentId("c${nextCommentId.incrementAndFetch()}")
    val created = ReviewComment(id, comment.filePath, comment.start, comment.end, comment.body.trim(), CommentStatus.Unsent)
    updateComments(key) { it + created }
    return id
  }

  override fun deleteComment(sessionId: SessionId, commentId: CommentId) {
    val key = session(sessionId)?.repositoryRoot ?: return
    updateComments(key) { comments -> comments.filterNot { it.id == commentId && it.status == CommentStatus.Unsent } }
  }

  override fun pendingMessage(sessionId: SessionId): String? {
    val session = session(sessionId) ?: return null
    val review = current(sessionId) ?: return null
    val unsent = ordered(review)
    if (unsent.isEmpty()) return null
    return ReviewMessages.forSession(unsent, session.branch, review.baseRefOrMain())
  }

  override suspend fun sendToSession(sessionId: SessionId): LemonadeCodeResult<Int, ReviewError> {
    val session = session(sessionId) ?: return LemonadeCodeResult.failure(ReviewError.UnknownSession)
    val key = session.repositoryRoot ?: return LemonadeCodeResult.failure(ReviewError.UnknownSession)
    val review = current(sessionId) ?: return LemonadeCodeResult.failure(ReviewError.NothingToSend)
    val unsent = ordered(review)
    if (unsent.isEmpty()) return LemonadeCodeResult.failure(ReviewError.NothingToSend)
    val message = ReviewMessages.forSession(unsent, session.branch, review.baseRefOrMain())
    return when (val delivered = sessions.paste(sessionId, message)) {
      is LemonadeCodeResult.Failure -> LemonadeCodeResult.failure(ReviewError.DeliveryFailed(delivered.error.toString()))
      is LemonadeCodeResult.Success -> {
        markDelivered(key, unsent.map { it.id }.toSet(), CommentStatus.Sent(session.displayName))
        LemonadeCodeResult.success(unsent.size)
      }
    }
  }

  override suspend fun sendToNewSubSession(sessionId: SessionId): LemonadeCodeResult<SessionId, ReviewError> {
    val session = session(sessionId) ?: return LemonadeCodeResult.failure(ReviewError.UnknownSession)
    val key = session.repositoryRoot ?: return LemonadeCodeResult.failure(ReviewError.UnknownSession)
    val review = current(sessionId) ?: return LemonadeCodeResult.failure(ReviewError.NothingToSend)
    val unsent = ordered(review)
    if (unsent.isEmpty()) return LemonadeCodeResult.failure(ReviewError.NothingToSend)
    val prompt = ReviewMessages.forSubSession(unsent, session.branch, review.baseRefOrMain())
    val started = sessions.startSubSession(
      parentId = sessionId,
      location = SubSessionLocation.SameFolder,
      initialInput = prompt,
      origin = SessionOrigin.Review(unsent.size),
      namePrefix = SessionService.ReviewPrefix,
    )
    return when (started) {
      is LemonadeCodeResult.Failure -> LemonadeCodeResult.failure(ReviewError.DeliveryFailed(started.error.toString()))
      is LemonadeCodeResult.Success -> {
        val name = sessions.sessions.value.firstOrNull { it.id == started.value }?.displayName ?: SessionService.ReviewPrefix
        markDelivered(key, unsent.map { it.id }.toSet(), CommentStatus.InSubSession(started.value, name))
        LemonadeCodeResult.success(started.value)
      }
    }
  }

  override suspend fun fileContent(
    sessionId: SessionId,
    path: String,
    version: FileVersion,
  ): LemonadeCodeResult<FileContent, ReviewError> {
    val target = target(sessionId) ?: return LemonadeCodeResult.failure(ReviewError.UnknownSession)
    val key = target.key.root
    val snapshot = (loaded.value.diffs[target.key] as? DiffState.Ready)?.snapshot
    val file = snapshot?.files?.firstOrNull { it.path == path }
      ?: return LemonadeCodeResult.failure(ReviewError.ReadFailed("$path isn't in the diff"))
    val read = when (version) {
      FileVersion.Base -> {
        val mergeBase = snapshot.mergeBase
        when {
          file.change == FileChange.Added -> return LemonadeCodeResult.success(FileContent.Missing)
          mergeBase == null -> return LemonadeCodeResult.failure(ReviewError.ReadFailed("The merge base with ${snapshot.baseRef} isn't known"))
          else -> git.readFileAt(key, mergeBase, file.previousPath ?: file.path, ReviewService.MaxPreviewBytes)
        }
      }
      FileVersion.WorkingTree -> {
        if (file.change == FileChange.Deleted) return LemonadeCodeResult.success(FileContent.Missing)
        git.readWorkingFile(key, file.path, ReviewService.MaxPreviewBytes)
      }
    }
    return read.asFileContent()
  }

  override suspend fun checkoutFile(
    sessionId: SessionId,
    path: String,
    version: FileVersion,
  ): LemonadeCodeResult<FileContent, ReviewError> {
    val target = target(sessionId) ?: return LemonadeCodeResult.failure(ReviewError.UnknownSession)
    if (!isCheckoutPath(path)) return LemonadeCodeResult.failure(ReviewError.ReadFailed("$path isn't a path inside the checkout"))
    val read = when (version) {
      FileVersion.Base -> {
        val snapshot = (loaded.value.diffs[target.key] as? DiffState.Ready)?.snapshot
        val mergeBase = snapshot?.mergeBase ?: return LemonadeCodeResult.failure(ReviewError.ReadFailed("The merge base isn't known"))
        git.readFileAt(target.key.root, mergeBase, path, ReviewService.MaxPreviewBytes)
      }
      FileVersion.WorkingTree -> git.readWorkingFile(target.key.root, path, ReviewService.MaxPreviewBytes)
    }
    return read.asFileContent()
  }

  override suspend fun webImage(url: String): LemonadeCodeResult<FileContent, ReviewError> {
    val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
    if (scheme != "https" && scheme != "http") return LemonadeCodeResult.failure(ReviewError.ReadFailed("$url isn't a web address"))
    webImages.get(url)?.let { return LemonadeCodeResult.success(it) }
    return web.read(url, ReviewService.MaxWebImageBytes)
      .map { content ->
        if (content is FileContent.Bytes) webImages.put(url, content)
        content
      }
      .mapError { ReviewError.ReadFailed(it) }
  }

  private fun LemonadeCodeResult<GitBlob, GitError>.asFileContent(): LemonadeCodeResult<FileContent, ReviewError> = map { blob ->
    when (blob) {
      GitBlob.Missing -> FileContent.Missing
      is GitBlob.TooLarge -> FileContent.TooLarge(blob.sizeBytes)
      is GitBlob.Content -> FileContent.Bytes(blob.bytes)
    }
  }.mapError { error ->
    ReviewError.ReadFailed(
      when (error) {
        GitError.NotARepository -> "Not a git repository"
        is GitError.RefNotFound -> "${error.ref} wasn't found"
        is GitError.CommandFailed -> error.message
        is GitError.Unavailable -> error.message
      },
    )
  }

  /** Re-reads the checkout's branches and the diff of [key]. */
  private suspend fun refresh(key: DiffKey) {
    val lock = refreshLocksGuard.withLock { refreshLocks.getOrPut(key) { Mutex() } }
    if (!lock.tryLock()) return
    try {
      val refs = Branches(
        default = git.defaultBranch(key.root).getOrNull(),
        all = git.branches(key.root).getOrElse { emptyList() },
      )
      val state = loadDiff(key.root, key.base)
      loaded.update { it.copy(diffs = it.diffs + (key to state), branches = it.branches + (key.root to refs)) }
    } finally {
      lock.unlock()
    }
  }

  private fun updateComments(key: String, transform: (List<ReviewComment>) -> List<ReviewComment>) {
    comments.update { all -> all + (key to transform(all[key].orEmpty())) }
  }

  private fun markDelivered(key: String, ids: Set<CommentId>, status: CommentStatus) = updateComments(key) { comments -> comments.map { if (it.id in ids) it.copy(status = status) else it } }

  /** Unsent comments in the order of the files in the diff, then by line. */
  private fun ordered(review: Review): List<ReviewComment> {
    val fileOrder = (review.diff as? DiffState.Ready)?.snapshot?.files?.withIndex()?.associate { it.value.path to it.index }.orEmpty()
    return review.unsent.sortedWith(compareBy({ fileOrder[it.filePath] ?: Int.MAX_VALUE }, { it.filePath }, { it.start.line }))
  }

  private fun Review.baseRefOrMain(): String = diff.baseRef() ?: base ?: "main"

  private fun DiffState.baseRef(): String? = when (this) {
    is DiffState.Ready -> snapshot.baseRef
    is DiffState.NoBaseBranch -> baseRef
    else -> null
  }

  /** What [sessionId]'s review shows now: its diff and its checkout's comments. */
  private fun current(sessionId: SessionId): Review? {
    val target = target(sessionId) ?: return null
    val diff = loaded.value.diffs[target.key] ?: return null
    return Review(diff, comments.value[target.key.root].orEmpty(), base = target.key.base)
  }

  private fun target(sessionId: SessionId): Target? = target(sessionId, sessions.sessions.value, chosenBases.value)

  /** The checkout [sessionId] works in and the branch its diff compares with; null outside git. */
  private fun target(sessionId: SessionId, all: List<Session>, chosen: Map<SessionId, String>): Target? {
    val session = all.firstOrNull { it.id == sessionId } ?: return null
    val root = session.repositoryRoot ?: return null
    val parentBranch = session.parentId?.let { parentId -> all.firstOrNull { it.id == parentId } }?.branch
    return Target(DiffKey(root, chosen[sessionId] ?: parentBranch), parentBranch)
  }

  private fun session(id: SessionId): Session? = sessions.sessions.value.firstOrNull { it.id == id }

  /** A diff of the checkout at [root] against [base]; null [base] is the checkout's default branch. */
  private data class DiffKey(val root: String, val base: String?)

  private data class Target(val key: DiffKey, val parentBranch: String?)

  /** A checkout's default branch, if it has one, and its local branches as git lists them. */
  private data class Branches(val default: String?, val all: List<String>)

  /** What git said last: every diff read, and each checkout's branches. One state, so they change together. */
  private data class Loaded(
    val diffs: Map<DiffKey, DiffState> = emptyMap(),
    val branches: Map<String, Branches> = emptyMap(),
  )
}

/**
 * The branches a diff can compare with, in the order to offer them: the default branch first, the
 * parent session's branch second, then the rest as git lists them.
 */
internal fun baseOptions(defaultBranch: String?, parentBranch: String?, listed: List<String>): List<String> = (listOfNotNull(defaultBranch, parentBranch) + listed).distinct()
