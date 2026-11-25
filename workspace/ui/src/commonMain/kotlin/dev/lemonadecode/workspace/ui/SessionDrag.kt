package dev.lemonadecode.workspace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.lemonadeShadow
import dev.lemonadecode.platform.designsystem.LemonadeCodeAnimation
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.workspace.uimodel.SessionNavigationModel
import dev.lemonadecode.workspace.uimodel.SessionTreeRowModel
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * A session's row picked up in the tree and moved among its siblings. The row stays where it is,
 * faded with the rows nested under it, while a lifted copy follows the pointer; the presenter says
 * where letting go would put it, drawn as a line between rows. Once let go, the copy glides into
 * the row's place, wherever that is by then, or jumps there with animations off.
 */
@Stable
internal class SessionDragState(private val scope: CoroutineScope) {
  /** The rows on show, as the tree last drew them. */
  var rows: List<SessionTreeRowModel> = emptyList()

  /** Settings › Appearance › Animations. */
  var animate: Boolean = true

  /** Where the tree is, and each row's surface by session id, in the root, unclipped by scrolling. */
  private var area by mutableStateOf(Rect.Zero)
  private val surfaces = mutableStateMapOf<String, Rect>()

  /** The session whose lifted row follows the pointer, or glides into place after; null otherwise. */
  var draggedId: String? by mutableStateOf(null)
    private set

  /** Where the lifted row's top would be, following the pointer, in the tree. */
  private var top by mutableFloatStateOf(0f)

  /** The pointer is down on [draggedId] and the drag wasn't called off. */
  private var dragging = false

  /** The presenter has said it is dragging [draggedId], so its saying nothing again means Esc. */
  private var seenByPresenter = false
  private var lastIndex: Int? = null
  private var glide: Job? = null

  fun placeTree(coordinates: LayoutCoordinates) {
    val bounds = coordinates.unclippedBounds()
    if (bounds != area) area = bounds
  }

  fun placeRow(id: String, coordinates: LayoutCoordinates) {
    val bounds = coordinates.unclippedBounds()
    if (surfaces[id] != bounds) surfaces[id] = bounds
  }

  /** Lets go of the rows that are no longer on show. */
  fun keepOnly(ids: Set<String>) {
    surfaces.keys.filter { it !in ids }.forEach(surfaces::remove)
  }

  /** [id]'s row in the tree, without the rails before it. */
  fun surface(id: String): Rect? = surfaces[id]?.translate(-area.left, -area.top)

  /** Where [id]'s lifted row is drawn: following the pointer, kept within the tree, or in its own place. */
  fun lifted(id: String): Rect? {
    val surface = surface(id) ?: return null
    if (draggedId != id) return surface
    val y = clamped(top, surface.height)
    return Rect(surface.left, y, surface.right, y + surface.height)
  }

  fun isDragging(id: String): Boolean = dragging && draggedId == id

  /** Picks up [id]'s row; false while it hasn't been laid out yet. */
  fun pickUp(id: String): Boolean {
    // One still gliding into place lands at once.
    glide?.cancel()
    draggedId = null
    val surface = surface(id) ?: return false
    draggedId = id
    top = surface.top
    dragging = true
    seenByPresenter = false
    lastIndex = currentIndex()
    return true
  }

  /** Moves the lifted row by [dy]; the place among its siblings it would now go to, when that changed. */
  fun moveBy(dy: Float): Int? {
    if (!dragging) return null
    top += dy
    val index = currentIndex() ?: return null
    if (index == lastIndex) return null
    lastIndex = index
    return index
  }

  /** Where the lifted row would go among its siblings, from where its middle is now. */
  private fun currentIndex(): Int? {
    val id = draggedId ?: return null
    val surface = surface(id) ?: return null
    val at = rows.indexOfFirst { it.id == id }
    if (at < 0) return null
    val places = rows.map { row -> surface(row.id)?.let { RowPlace(row.depth, it.top, it.bottom) } ?: return null }
    return dropIndex(places, at, clamped(top, surface.height) + surface.height / 2)
  }

  /** The presenter's drag; when it ends while the pointer is still down, Esc called it off. */
  fun onPresenterDrag(sessionId: String?) {
    when {
      sessionId != null && sessionId == draggedId -> seenByPresenter = true
      sessionId == null && dragging && seenByPresenter -> letGo()
    }
  }

  /** Let go, or called off: the lifted row glides into its row's place, wherever that is now. */
  fun letGo() {
    val id = draggedId ?: return
    dragging = false
    glide?.cancel()
    val surface = surface(id)
    if (!animate || surface == null) {
      draggedId = null
      return
    }
    top = clamped(top, surface.height)
    glide = scope.launch {
      var last = withFrameNanos { it }
      val end = last + GlideMillis * 1_000_000L
      while (true) {
        val now = withFrameNanos { it }
        // Read each frame: the row moves to its new place a frame or two after letting go.
        val target = surface(id)?.top ?: break
        val left = target - top
        if (abs(left) < 0.5f || now >= end) break
        top += left * (1f - exp(-(now - last) / GlideTimeConstantNanos))
        last = now
      }
      draggedId = null
    }
  }

  private fun clamped(y: Float, height: Float): Float = y.coerceIn(-height / 2, (area.height - height / 2).coerceAtLeast(-height / 2))

  private fun LayoutCoordinates.unclippedBounds(): Rect = Rect(positionInRoot(), size.toSize())

  private companion object {
    /** The glide is over in about this long, however far it goes. */
    const val GlideMillis = LemonadeCodeAnimation.ItemMillis

    /** A quarter of it: by the end, less than 2% of the way is left. */
    const val GlideTimeConstantNanos = GlideMillis * 1_000_000f / 4
  }
}

@Composable
internal fun rememberSessionDragState(): SessionDragState {
  val scope = rememberCoroutineScope()
  return remember(scope) { SessionDragState(scope) }
}

/** The depth of a row on show and where its surface is, top to bottom. */
internal class RowPlace(
  val depth: Int,
  val top: Float,
  val bottom: Float,
)

/**
 * Where the row at [dragged] would go among its siblings, counted as they'll be once it has moved,
 * with its middle at [y]. A sibling's rows, its own and those nested under it, count as one: once
 * the middle passes theirs, it goes beyond that sibling. [rows] are the rows on show, in order.
 */
internal fun dropIndex(rows: List<RowPlace>, dragged: Int, y: Float): Int {
  val depth = rows[dragged].depth
  // Its siblings and what's nested under them: up to the rows less deep on either side.
  var first = dragged
  while (first > 0 && rows[first - 1].depth >= depth) first--
  var last = dragged
  while (last + 1 < rows.size && rows[last + 1].depth >= depth) last++
  var index = 0
  for (at in first..last) {
    if (at == dragged || rows[at].depth != depth) continue
    var end = at
    while (end + 1 <= last && rows[end + 1].depth > depth) end++
    if ((rows[at].top + rows[end].bottom) / 2 < y) index++
  }
  return index
}

/** The rows that move with [draggedId]'s: it and the rows nested under it, which follow it. */
internal fun movingRows(rows: List<SessionTreeRowModel>, draggedId: String?): Set<String> {
  val at = rows.indexOfFirst { it.id == draggedId }
  if (at < 0) return emptySet()
  var end = at
  while (end + 1 < rows.size && rows[end + 1].depth > rows[at].depth) end++
  return rows.subList(at, end + 1).mapTo(HashSet()) { it.id }
}

/**
 * The tree's rows, drawn by [content] with the rows that move with a dragged one, and over them the
 * line where it would go and its lifted row.
 */
@Composable
internal fun SessionTreeDragArea(
  tree: SessionNavigationModel.Tree,
  state: SessionDragState,
  modifier: Modifier = Modifier,
  content: @Composable (moving: Set<String>) -> Unit,
) {
  val rows = tree.rows
  val animate = LemonadeCodeTheme.animations
  SideEffect {
    state.rows = rows
    state.animate = animate
    state.keepOnly(rows.mapTo(HashSet()) { it.id })
  }
  val presenterDrag = tree.drag?.sessionId
  LaunchedEffect(state, presenterDrag) { state.onPresenterDrag(presenterDrag) }
  val draggedId = state.draggedId ?: presenterDrag
  val moving = remember(rows, draggedId) { movingRows(rows, draggedId) }
  val drop = tree.drag?.drop
  val line = LemonadeTheme.colors.border.borderSelected
  Box(modifier = modifier.onGloballyPositioned(state::placeTree)) {
    content(moving)
    Box(
      modifier = Modifier
        .matchParentSize()
        .drawBehind {
          val dragged = draggedId?.let(state::surface) ?: return@drawBehind
          if (drop == null) return@drawBehind
          val at = rows.indexOfFirst { it.id == drop.rowId }
          val row = state.surface(drop.rowId) ?: return@drawBehind
          // Halfway across any gap between the two rows it goes between.
          val y = if (drop.below) {
            rows.getOrNull(at + 1)?.let(SessionTreeRowModel::id)?.let(state::surface)?.let { (row.bottom + it.top) / 2 } ?: row.bottom
          } else {
            rows.getOrNull(at - 1)?.let(SessionTreeRowModel::id)?.let(state::surface)?.let { (it.bottom + row.top) / 2 } ?: row.top
          }
          val stroke = DropLineWidth.toPx()
          val radius = DropDotRadius.toPx()
          val start = dragged.left + radius + stroke / 2
          drawCircle(color = line, radius = radius, center = Offset(start, y), style = Stroke(width = stroke))
          drawLine(color = line, start = Offset(start + radius, y), end = Offset(dragged.right, y), strokeWidth = stroke)
        },
    ) {
      rows.firstOrNull { it.id == draggedId }?.let { row ->
        LiftedSessionRow(row = row, modifier = Modifier.placedAt(state, row.id).clearAndSetSemantics {})
      }
    }
  }
}

/**
 * Lets a tree row be picked up and moved among its siblings: pressed and moved up or down with the
 * main button, or held a moment on a touch screen. A click still selects, and the right and middle
 * buttons still open the menu and close. Goes on the row's surface, before its click.
 */
internal fun Modifier.sessionDragSource(
  sessionId: String,
  state: SessionDragState,
  onEvent: (WorkspaceEvent) -> Unit,
): Modifier = onGloballyPositioned { state.placeRow(sessionId, it) }
  .pointerInput(sessionId, state, onEvent) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
      val picked = if (down.type == PointerType.Touch) {
        awaitLongPressOrCancellation(down.id)
      } else {
        awaitVerticalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
      }
      if (picked == null || !state.pickUp(sessionId)) return@awaitEachGesture
      onEvent(WorkspaceEvent.StartSessionDrag(sessionId))
      state.moveBy(picked.position.y - down.position.y)?.let { onEvent(WorkspaceEvent.DragSession(sessionId, it)) }
      val released = verticalDrag(picked.id) { change ->
        val dy = change.positionChange().y
        change.consume()
        state.moveBy(dy)?.let { onEvent(WorkspaceEvent.DragSession(sessionId, it)) }
      }
      // Called off with Esc meanwhile: the presenter has already let it go.
      if (state.isDragging(sessionId)) onEvent(if (released) WorkspaceEvent.DropSession(sessionId) else WorkspaceEvent.CancelSessionDrag)
      state.letGo()
    }
  }

/** Lays the lifted row out as wide as its row, where [state] says it is, over the tree. */
private fun Modifier.placedAt(state: SessionDragState, id: String): Modifier = layout { measurable, constraints ->
  val bounds = state.lifted(id)
  if (bounds == null) {
    layout(0, 0) {}
  } else {
    val placeable = measurable.measure(Constraints.fixedWidth(bounds.width.roundToInt().coerceAtLeast(0)))
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt()) }
  }
}

/**
 * A session's row lifted out of the tree: the same name, status and second line, on a raised card.
 * One with sub-sessions, which move with it, has a second card peeking out under it.
 */
@Composable
private fun LiftedSessionRow(
  row: SessionTreeRowModel,
  modifier: Modifier = Modifier,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  val shape = LemonadeTheme.shapes.radius300
  val border = LemonadeTheme.borderWidths.base.border25
  // The raised tints are see-through: laid on the panel's own colour, nothing shows through them.
  val base = colors.background.bgDefault
  Box(modifier = modifier) {
    if (row.fold != null) {
      Box(
        modifier = Modifier
          .matchParentSize()
          .padding(horizontal = spaces.spacing200)
          .offset(y = 5.dp)
          .lemonadeShadow(shadow = LemonadeShadow.Medium, shape = shape)
          .background(base, shape)
          .background(colors.background.bgElevated, shape)
          .border(border, colors.border.borderNeutralMedium, shape),
      )
    }
    Row(
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
      modifier = Modifier
        .fillMaxWidth()
        .lemonadeShadow(shadow = LemonadeShadow.Large, shape = shape)
        .background(base, shape)
        .background(colors.background.bgElevatedHigh, shape)
        .border(border, colors.border.borderNeutralHigh, shape)
        .padding(start = if (row.depth == 0) spaces.spacing300 else 6.dp, end = spaces.spacing200, top = 6.dp, bottom = 6.dp),
    ) {
      if (row.depth == 0) ComputerIcon(selected = true)
      SessionRowText(name = row.name, status = row.status, meta = row.meta, modifier = Modifier.weight(1f))
    }
  }
}

private val DropLineWidth = 2.dp
private val DropDotRadius = 3.dp
