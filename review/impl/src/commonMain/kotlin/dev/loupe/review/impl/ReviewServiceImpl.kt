package dev.loupe.review.impl

import dev.loupe.data.git.GitBlob
import dev.loupe.data.git.GitDataSource
import dev.loupe.data.git.GitError
import dev.loupe.domain.review.CommentId
import dev.loupe.domain.review.CommentStatus
import dev.loupe.domain.review.DiffState
import dev.loupe.domain.review.FileChange
import dev.loupe.domain.review.FileContent
import dev.loupe.domain.review.FileVersion
import dev.loupe.domain.review.NewComment
import dev.loupe.domain.review.Review
import dev.loupe.domain.review.ReviewComment
import dev.loupe.domain.session.Session
import dev.loupe.domain.session.SessionId
import dev.loupe.domain.session.SessionOrigin
import dev.loupe.domain.session.SubSessionLocation
import dev.loupe.platform.core.LoupeResult
import dev.loupe.platform.core.map
import dev.loupe.platform.core.mapError
import dev.loupe.platform.di.AppScope
import dev.loupe.review.api.ReviewError
import dev.loupe.review.api.ReviewService
import dev.loupe.sessions.api.SessionService
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

/** Reviews keyed by the git checkout a session works in. */
@OptIn(ExperimentalAtomicApi::class)
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
@Inject
internal class ReviewServiceImpl(
  private val sessions: SessionService,
  private val loadDiff: LoadDiffUseCase,
  private val git: GitDataSource,
  private val scope: CoroutineScope,
) : ReviewService {
  private val reviews = MutableStateFlow<Map<String, Review>>(emptyMap())
  private val refreshLocks = mutableMapOf<String, Mutex>()
  private val refreshLocksGuard = Mutex()
  private val nextCommentId = AtomicLong(0)

  @OptIn(ExperimentalCoroutinesApi::class)
  override fun review(sessionId: SessionId): Flow<Review> = sessions.sessions
    .map { list -> list.firstOrNull { it.id == sessionId }?.reviewKey() }
    .distinctUntilChanged()
    .flatMapLatest { key ->
      if (key == null) {
        flowOf(Review(DiffState.NotARepository, emptyList()))
      } else {
        reviews
          .map { it[key] ?: Review(DiffState.Loading, emptyList()) }
          .distinctUntilChanged()
          .onStart { if (reviews.value[key] == null) scope.launch { refreshKey(key) } }
      }
    }

  override suspend fun refresh(sessionId: SessionId) {
    session(sessionId)?.reviewKey()?.let { refreshKey(it) }
  }

  override fun addComment(sessionId: SessionId, comment: NewComment): CommentId? {
    val key = session(sessionId)?.reviewKey() ?: return null
    val id = CommentId("c${nextCommentId.incrementAndFetch()}")
    val created = ReviewComment(id, comment.filePath, comment.start, comment.end, comment.body.trim(), CommentStatus.Unsent)
    updateComments(key) { it + created }
    return id
  }

  override fun deleteComment(sessionId: SessionId, commentId: CommentId) {
    val key = session(sessionId)?.reviewKey() ?: return
    updateComments(key) { comments -> comments.filterNot { it.id == commentId && it.status == CommentStatus.Unsent } }
  }

  override fun pendingMessage(sessionId: SessionId): String? {
    val session = session(sessionId) ?: return null
    val review = session.reviewKey()?.let { reviews.value[it] } ?: return null
    val unsent = ordered(review)
    if (unsent.isEmpty()) return null
    return ReviewMessages.forSession(unsent, session.branch, review.baseRef())
  }

  override suspend fun sendToSession(sessionId: SessionId): LoupeResult<Int, ReviewError> {
    val session = session(sessionId) ?: return LoupeResult.failure(ReviewError.UnknownSession)
    val key = session.reviewKey() ?: return LoupeResult.failure(ReviewError.UnknownSession)
    val review = reviews.value[key] ?: return LoupeResult.failure(ReviewError.NothingToSend)
    val unsent = ordered(review)
    if (unsent.isEmpty()) return LoupeResult.failure(ReviewError.NothingToSend)
    val message = ReviewMessages.forSession(unsent, session.branch, review.baseRef())
    return when (val delivered = sessions.paste(sessionId, message)) {
      is LoupeResult.Failure -> LoupeResult.failure(ReviewError.DeliveryFailed(delivered.error.toString()))
      is LoupeResult.Success -> {
        markDelivered(key, unsent.map { it.id }.toSet(), CommentStatus.Sent(session.displayName))
        LoupeResult.success(unsent.size)
      }
    }
  }

  override suspend fun sendToNewSubSession(sessionId: SessionId): LoupeResult<SessionId, ReviewError> {
    val session = session(sessionId) ?: return LoupeResult.failure(ReviewError.UnknownSession)
    val key = session.reviewKey() ?: return LoupeResult.failure(ReviewError.UnknownSession)
    val review = reviews.value[key] ?: return LoupeResult.failure(ReviewError.NothingToSend)
    val unsent = ordered(review)
    if (unsent.isEmpty()) return LoupeResult.failure(ReviewError.NothingToSend)
    val prompt = ReviewMessages.forSubSession(unsent, session.branch, review.baseRef())
    val started = sessions.startSubSession(
      parentId = sessionId,
      location = SubSessionLocation.SameFolder,
      initialInput = prompt,
      origin = SessionOrigin.Review(unsent.size),
      namePrefix = SessionService.ReviewPrefix,
    )
    return when (started) {
      is LoupeResult.Failure -> LoupeResult.failure(ReviewError.DeliveryFailed(started.error.toString()))
      is LoupeResult.Success -> {
        val name = sessions.sessions.value.firstOrNull { it.id == started.value }?.displayName ?: SessionService.ReviewPrefix
        markDelivered(key, unsent.map { it.id }.toSet(), CommentStatus.InSubSession(started.value, name))
        LoupeResult.success(started.value)
      }
    }
  }

  override suspend fun fileContent(
    sessionId: SessionId,
    path: String,
    version: FileVersion,
  ): LoupeResult<FileContent, ReviewError> {
    val key = session(sessionId)?.reviewKey() ?: return LoupeResult.failure(ReviewError.UnknownSession)
    val snapshot = (reviews.value[key]?.diff as? DiffState.Ready)?.snapshot
    val file = snapshot?.files?.firstOrNull { it.path == path }
      ?: return LoupeResult.failure(ReviewError.ReadFailed("$path isn't in the diff"))
    val read = when (version) {
      FileVersion.Base -> {
        val mergeBase = snapshot.mergeBase
        when {
          file.change == FileChange.Added -> return LoupeResult.success(FileContent.Missing)
          mergeBase == null -> return LoupeResult.failure(ReviewError.ReadFailed("The merge base with ${snapshot.baseRef} isn't known"))
          else -> git.readFileAt(key, mergeBase, file.previousPath ?: file.path, ReviewService.MaxPreviewBytes)
        }
      }
      FileVersion.WorkingTree -> {
        if (file.change == FileChange.Deleted) return LoupeResult.success(FileContent.Missing)
        git.readWorkingFile(key, file.path, ReviewService.MaxPreviewBytes)
      }
    }
    return read
      .map { blob ->
        when (blob) {
          GitBlob.Missing -> FileContent.Missing
          is GitBlob.TooLarge -> FileContent.TooLarge(blob.sizeBytes)
          is GitBlob.Content -> FileContent.Bytes(blob.bytes)
        }
      }
      .mapError { error ->
        ReviewError.ReadFailed(
          when (error) {
            GitError.NotARepository -> "Not a git repository"
            is GitError.RefNotFound -> "${error.ref} wasn't found"
            is GitError.CommandFailed -> error.message
            is GitError.Unavailable -> error.message
          },
        )
      }
  }

  private suspend fun refreshKey(key: String) {
    val lock = refreshLocksGuard.withLock { refreshLocks.getOrPut(key) { Mutex() } }
    if (!lock.tryLock()) return
    try {
      val state = loadDiff(key)
      reviews.update { all -> all + (key to Review(state, all[key]?.comments.orEmpty())) }
    } finally {
      lock.unlock()
    }
  }

  private fun updateComments(key: String, transform: (List<ReviewComment>) -> List<ReviewComment>) {
    reviews.update { all ->
      val current = all[key] ?: Review(DiffState.Loading, emptyList())
      all + (key to current.copy(comments = transform(current.comments)))
    }
  }

  private fun markDelivered(key: String, ids: Set<CommentId>, status: CommentStatus) = updateComments(key) { comments -> comments.map { if (it.id in ids) it.copy(status = status) else it } }

  /** Unsent comments in the order of the files in the diff, then by line. */
  private fun ordered(review: Review): List<ReviewComment> {
    val fileOrder = (review.diff as? DiffState.Ready)?.snapshot?.files?.withIndex()?.associate { it.value.path to it.index }.orEmpty()
    return review.unsent.sortedWith(compareBy({ fileOrder[it.filePath] ?: Int.MAX_VALUE }, { it.filePath }, { it.start.line }))
  }

  private fun Review.baseRef(): String = (diff as? DiffState.Ready)?.snapshot?.baseRef ?: "main"

  private fun session(id: SessionId): Session? = sessions.sessions.value.firstOrNull { it.id == id }

  private fun Session.reviewKey(): String? = repositoryRoot
}
