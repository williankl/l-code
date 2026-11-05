package dev.lemonadecode.platform.designsystem

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn as composeFadeIn
import androidx.compose.animation.fadeOut as composeFadeOut

/** Whether things may animate; Settings › Appearance › Animations. */
internal val LocalAnimations = staticCompositionLocalOf { true }

/** Where a panel slides in from and back out to. */
public enum class AnimationEdge { Start, End, Bottom }

/**
 * Lemonade Code's motion: short, quiet and never in the way. Nothing runs longer than a quarter
 * of a second, and with Animations off in settings every spec here snaps and every transition is
 * [EnterTransition.None] or [ExitTransition.None], so callers never check the switch themselves.
 */
public object LemonadeCodeAnimation {
  /** A popover or menu opening. */
  public const val PopoverInMillis: Int = 150

  /** A popover or menu closing: quicker than it opened, so it's out of the way. */
  public const val PopoverOutMillis: Int = 100

  /** A panel sliding in or a width changing. */
  public const val PanelMillis: Int = 220

  /** One view giving way to another. */
  public const val FadeMillis: Int = 150

  /** A row joining or leaving a list. */
  public const val ItemMillis: Int = 180

  /** A [tween] while animations are on, otherwise a jump straight to the end. */
  @Composable
  @ReadOnlyComposable
  public fun <T> spec(
    durationMillis: Int = PanelMillis,
    easing: Easing = FastOutSlowInEasing,
  ): FiniteAnimationSpec<T> = if (LocalAnimations.current) tween(durationMillis, easing = easing) else snap()

  /** A [tween] while animations are on, otherwise null; for APIs where null means "don't animate". */
  @Composable
  @ReadOnlyComposable
  public fun <T> specOrNull(
    durationMillis: Int = ItemMillis,
    easing: Easing = FastOutSlowInEasing,
  ): FiniteAnimationSpec<T>? = if (LocalAnimations.current) tween(durationMillis, easing = easing) else null

  @Composable
  @ReadOnlyComposable
  public fun fadeIn(durationMillis: Int = FadeMillis): EnterTransition = enter {
    composeFadeIn(tween(durationMillis, easing = LinearOutSlowInEasing))
  }

  @Composable
  @ReadOnlyComposable
  public fun fadeOut(durationMillis: Int = FadeMillis): ExitTransition = exit {
    composeFadeOut(tween(durationMillis, easing = FastOutLinearInEasing))
  }

  /** Grows a little from [origin], the corner nearest what opened it, as it fades in. */
  @Composable
  @ReadOnlyComposable
  public fun popoverIn(origin: TransformOrigin): EnterTransition = enter {
    composeFadeIn(tween(PopoverInMillis, easing = LinearOutSlowInEasing)) +
      scaleIn(tween(PopoverInMillis, easing = FastOutSlowInEasing), initialScale = 0.95f, transformOrigin = origin)
  }

  @Composable
  @ReadOnlyComposable
  public fun popoverOut(origin: TransformOrigin): ExitTransition = exit {
    composeFadeOut(tween(PopoverOutMillis, easing = FastOutLinearInEasing)) +
      scaleOut(tween(PopoverOutMillis, easing = FastOutLinearInEasing), targetScale = 0.97f, transformOrigin = origin)
  }

  /** Slides in from [edge]: the whole way from a side, or half its height from the bottom, fading in. */
  @Composable
  @ReadOnlyComposable
  public fun slideIn(edge: AnimationEdge): EnterTransition = enter {
    val spec = tween<IntOffset>(PanelMillis, easing = FastOutSlowInEasing)
    val slide = when (edge) {
      AnimationEdge.Start -> slideInHorizontally(spec) { -it }
      AnimationEdge.End -> slideInHorizontally(spec) { it }
      AnimationEdge.Bottom -> slideInVertically(spec) { it / 2 }
    }
    slide + composeFadeIn(tween(PanelMillis, easing = LinearOutSlowInEasing))
  }

  @Composable
  @ReadOnlyComposable
  public fun slideOut(edge: AnimationEdge): ExitTransition = exit {
    val spec = tween<IntOffset>(PanelMillis, easing = FastOutSlowInEasing)
    val slide = when (edge) {
      AnimationEdge.Start -> slideOutHorizontally(spec) { -it }
      AnimationEdge.End -> slideOutHorizontally(spec) { it }
      AnimationEdge.Bottom -> slideOutVertically(spec) { it / 2 }
    }
    slide + composeFadeOut(tween(PanelMillis, easing = FastOutLinearInEasing))
  }

  /** Opens downwards from nothing, pushing what's below, as it fades in. */
  @Composable
  @ReadOnlyComposable
  public fun expandIn(durationMillis: Int = ItemMillis): EnterTransition = enter {
    expandVertically(tween(durationMillis, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
      composeFadeIn(tween(durationMillis, easing = LinearOutSlowInEasing))
  }

  @Composable
  @ReadOnlyComposable
  public fun collapseOut(durationMillis: Int = ItemMillis): ExitTransition = exit {
    shrinkVertically(tween(durationMillis, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
      composeFadeOut(tween(durationMillis, easing = FastOutLinearInEasing))
  }

  /**
   * Widens from nothing, pushing its neighbours aside. The content stays pinned to [from]: a panel
   * on the right pinned to its start edge slides in from the window's edge as it widens.
   */
  @Composable
  @ReadOnlyComposable
  public fun expandSideways(from: Alignment.Horizontal): EnterTransition = enter {
    expandHorizontally(tween(PanelMillis, easing = FastOutSlowInEasing), expandFrom = from) +
      composeFadeIn(tween(PanelMillis, easing = LinearOutSlowInEasing))
  }

  @Composable
  @ReadOnlyComposable
  public fun shrinkSideways(towards: Alignment.Horizontal): ExitTransition = exit {
    shrinkHorizontally(tween(PanelMillis, easing = FastOutSlowInEasing), shrinkTowards = towards) +
      composeFadeOut(tween(PanelMillis, easing = FastOutLinearInEasing))
  }

  @Composable
  @ReadOnlyComposable
  private inline fun enter(transition: () -> EnterTransition): EnterTransition = if (LocalAnimations.current) transition() else EnterTransition.None

  @Composable
  @ReadOnlyComposable
  private inline fun exit(transition: () -> ExitTransition): ExitTransition = if (LocalAnimations.current) transition() else ExitTransition.None
}

/**
 * [value] while it's set, and the last value it had once it's null, so something can go on
 * drawing it while it animates away.
 */
@Composable
public fun <T : Any> rememberLastNonNull(value: T?): T? {
  val last = remember { LastValue<T>() }
  if (value != null) last.value = value
  return value ?: last.value
}

private class LastValue<T : Any> {
  var value: T? = null
}

/**
 * Shows [content] for [value] while it's set; when it turns null, the last value goes on showing
 * while [exit] plays. Shown from the start, it doesn't animate in.
 */
@Composable
public fun <T : Any> AnimatedPresence(
  value: T?,
  modifier: Modifier = Modifier,
  enter: EnterTransition = LemonadeCodeAnimation.fadeIn(),
  exit: ExitTransition = LemonadeCodeAnimation.fadeOut(),
  content: @Composable AnimatedVisibilityScope.(T) -> Unit,
) {
  val shown = rememberLastNonNull(value)
  AnimatedVisibility(visible = value != null, modifier = modifier, enter = enter, exit = exit) {
    if (shown != null) content(shown)
  }
}

/**
 * The [items] of a short list, each in the parent layout, opening with [enter] when it's added and
 * closing with [exit] where it was when it's removed, so the items below move along smoothly.
 * The items there at first don't animate. Every item stays composed, unlike a lazy list's.
 * [content] gets each item's place among [items] (its last place, for one on its way out).
 */
@Composable
public fun <T> AnimatedItems(
  items: List<T>,
  key: (T) -> Any,
  enter: EnterTransition = LemonadeCodeAnimation.expandIn(),
  exit: ExitTransition = LemonadeCodeAnimation.collapseOut(),
  content: @Composable AnimatedVisibilityScope.(index: Int, item: T) -> Unit,
) {
  val list = remember { AnimatedItemList<T>() }
  list.update(items, key).forEach { entry ->
    key(entry.key) {
      AnimatedVisibility(visibleState = entry.state, enter = enter, exit = exit) {
        content(entry.index, entry.item)
      }
    }
  }
}

private class AnimatedItemEntry<T>(
  val key: Any,
  var item: T,
  var index: Int,
  val state: MutableTransitionState<Boolean>,
)

/** The items on show: the current ones, plus removed ones still closing, at the place they left. */
private class AnimatedItemList<T> {
  private var entries: List<AnimatedItemEntry<T>> = emptyList()
  private var first = true

  fun update(items: List<T>, key: (T) -> Any): List<AnimatedItemEntry<T>> {
    val old = entries.associateBy { it.key }
    val next = ArrayList<AnimatedItemEntry<T>>(items.size)
    items.forEachIndexed { index, item ->
      val itemKey = key(item)
      val entry = old[itemKey]?.also {
        it.item = item
        it.index = index
        it.state.targetState = true
      } ?: AnimatedItemEntry(itemKey, item, index, MutableTransitionState(first).apply { targetState = true })
      next.add(entry)
    }
    val present = next.mapTo(HashSet()) { it.key }
    entries.forEachIndexed { position, entry ->
      if (entry.key in present) return@forEachIndexed
      entry.state.targetState = false
      // Gone once it has finished closing.
      if (!entry.state.currentState && entry.state.isIdle) return@forEachIndexed
      // Back where it was: just after the nearest item that was above it and still shows.
      val above = (position - 1 downTo 0).firstNotNullOfOrNull { abovePosition ->
        next.indexOfFirst { it.key == entries[abovePosition].key }.takeIf { it >= 0 }
      }
      next.add((above ?: -1) + 1, entry)
    }
    first = false
    entries = next
    return next
  }
}
