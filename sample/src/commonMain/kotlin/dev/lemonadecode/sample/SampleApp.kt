package dev.lemonadecode.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Button
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SegmentedControl
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeSegmentedControlSize
import com.teya.lemonade.core.LemonadeShadow
import com.teya.lemonade.core.TabButtonProperties
import com.teya.lemonade.core.TagVoice
import com.teya.lemonade.lemonadeShadow
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.LemonadeCodeThemeMode
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcase
import dev.lemonadecode.workspace.impl.showcase.WorkspaceShowcaseContent
import dev.lemonadecode.workspace.ui.toThemeMode
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent

private enum class ThemeChoice(val label: String) { Design("Design"), Dark("Dark"), Light("Light"), System("System") }

private enum class FrameChoice(val label: String) { Design("Design size"), Fill("Fill window") }

private data class LoggedAction(val id: Int, val sample: WorkspaceShowcase, val action: WorkspaceEvent)

/**
 * A harness for looking at the Lemonade Code UI. It shows the design's screens as static states; nothing
 * reacts to clicks except this panel. Every action the screen raises is listed in the log so the
 * wiring can be checked.
 */
@Composable
public fun SampleApp(onAction: (WorkspaceEvent) -> Unit = {}) {
  var sample by remember { mutableStateOf(WorkspaceShowcase.AgentFinishes) }
  var theme by remember { mutableStateOf(ThemeChoice.Design) }
  var frame by remember { mutableStateOf(FrameChoice.Design) }
  val log = remember { mutableStateListOf<LoggedAction>() }
  var nextId by remember { mutableIntStateOf(0) }

  val mode = when (theme) {
    ThemeChoice.Design -> sample.model().settings.theme.toThemeMode()
    ThemeChoice.Dark -> LemonadeCodeThemeMode.Dark
    ThemeChoice.Light -> LemonadeCodeThemeMode.Light
    ThemeChoice.System -> LemonadeCodeThemeMode.System
  }
  val samples = WorkspaceShowcase.entries

  LemonadeCodeTheme(mode = mode) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .background(LemonadeTheme.colors.background.bgSubtle)
        .onPreviewKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown || !event.isMetaPressed) return@onPreviewKeyEvent false
          val step = when (event.key) {
            Key.DirectionDown -> 1
            Key.DirectionUp -> -1
            else -> return@onPreviewKeyEvent false
          }
          sample = samples[(sample.ordinal + step).mod(samples.size)]
          true
        },
    ) {
      ControlPanel(
        selected = sample,
        onSelect = { sample = it },
        theme = theme,
        onTheme = { theme = it },
        frame = frame,
        onFrame = { frame = it },
        log = log,
        onClearLog = { log.clear() },
        modifier = Modifier.width(320.dp).fillMaxHeight(),
      )
      Stage(
        sample = sample,
        frame = frame,
        modifier = Modifier.weight(1f).fillMaxHeight(),
      ) {
        WorkspaceShowcaseContent(
          showcase = sample,
          onEvent = { event ->
            log.add(0, LoggedAction(nextId++, sample, event))
            if (log.size > 200) log.removeAt(log.lastIndex)
            onAction(event)
          },
        )
      }
    }
  }
}

@Composable
private fun Stage(
  sample: WorkspaceShowcase,
  frame: FrameChoice,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val spaces = LemonadeTheme.spaces
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = modifier.padding(spaces.spacing600),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
      ) {
        LemonadeUi.Tag(label = sample.code, voice = TagVoice.Neutral)
        LemonadeUi.Text(text = sample.title, textStyle = LemonadeTheme.typography.headingXSmall)
      }
      LemonadeUi.Text(
        text = sample.description,
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        color = LemonadeTheme.colors.content.contentSecondary,
      )
    }
    val shape = LemonadeTheme.shapes.radius500
    when (frame) {
      FrameChoice.Fill -> Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .lemonadeShadow(LemonadeShadow.Xlarge, shape)
          .clip(shape),
      ) { content() }
      FrameChoice.Design -> Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .horizontalScroll(rememberScrollState()),
      ) {
        Box(
          modifier = Modifier
            .padding(spaces.spacing200)
            .size(width = sample.widthDp.dp, height = sample.heightDp.dp)
            .lemonadeShadow(LemonadeShadow.Xlarge, shape)
            .clip(shape),
        ) { content() }
      }
    }
  }
}

@Composable
private fun ControlPanel(
  selected: WorkspaceShowcase,
  onSelect: (WorkspaceShowcase) -> Unit,
  theme: ThemeChoice,
  onTheme: (ThemeChoice) -> Unit,
  frame: FrameChoice,
  onFrame: (FrameChoice) -> Unit,
  log: List<LoggedAction>,
  onClearLog: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  Column(
    verticalArrangement = Arrangement.spacedBy(spaces.spacing400),
    modifier = modifier
      .background(colors.background.bgDefault)
      .padding(spaces.spacing400),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
      LemonadeUi.Text(text = "Lemonade Code UI sample", textStyle = LemonadeTheme.typography.headingXSmall)
      LemonadeUi.Text(
        text = "Static screens from the design. Nothing reacts; every action is logged below. ⌘↑ ⌘↓ switch screens.",
        textStyle = LemonadeTheme.typography.bodyXSmallRegular,
        color = colors.content.contentSecondary,
      )
    }
    PanelSection(title = "Theme") {
      LemonadeUi.SegmentedControl(
        properties = ThemeChoice.entries.map { TabButtonProperties.label(it.label) },
        selectedTab = theme.ordinal,
        onTabSelected = { onTheme(ThemeChoice.entries[it]) },
        size = LemonadeSegmentedControlSize.Small,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    PanelSection(title = "Frame") {
      LemonadeUi.SegmentedControl(
        properties = FrameChoice.entries.map { TabButtonProperties.label(it.label) },
        selectedTab = frame.ordinal,
        onTabSelected = { onFrame(FrameChoice.entries[it]) },
        size = LemonadeSegmentedControlSize.Small,
        modifier = Modifier.fillMaxWidth(),
      )
    }
    PanelSection(title = "Screen") {
      Column(verticalArrangement = Arrangement.spacedBy(spaces.spacing50)) {
        WorkspaceShowcase.entries.forEach { sample ->
          ScreenRow(sample = sample, selected = sample == selected, onClick = { onSelect(sample) })
        }
      }
    }
    PanelSection(
      title = "Action log",
      modifier = Modifier.weight(1f),
      trailing = {
        LemonadeUi.Button(
          label = "Clear",
          onClick = onClearLog,
          variant = LemonadeButtonVariant.Neutral,
          type = LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.XSmall,
          enabled = log.isNotEmpty(),
        )
      },
    ) {
      if (log.isEmpty()) {
        LemonadeUi.Text(
          text = "Click, hover, drag from a + or type in the composer to see the actions the UI raises.",
          textStyle = LemonadeTheme.typography.bodyXSmallRegular,
          color = colors.content.contentTertiary,
        )
      } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(spaces.spacing100)) {
          items(log, key = { it.id }) { entry ->
            LemonadeUi.Text(
              text = entry.action.toString(),
              textStyle = LemonadeCodeTheme.typography.codeSmall.copy(color = colors.content.contentPrimary),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun PanelSection(
  title: String,
  modifier: Modifier = Modifier,
  trailing: (@Composable () -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  Column(
    verticalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    modifier = modifier,
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp),
    ) {
      LemonadeUi.Text(
        text = title.uppercase(),
        textStyle = LemonadeTheme.typography.bodyXSmallOverline,
        color = LemonadeTheme.colors.content.contentSecondary,
      )
      trailing?.invoke()
    }
    content()
  }
}

@Composable
private fun ScreenRow(
  sample: WorkspaceShowcase,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  val interactionSource = remember { MutableInteractionSource() }
  val hovered by interactionSource.collectIsHoveredAsState()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(LemonadeTheme.spaces.spacing200),
    modifier = Modifier
      .fillMaxWidth()
      .clip(LemonadeTheme.shapes.radius300)
      .background(
        when {
          selected -> colors.background.bgElevatedHigh
          hovered -> colors.background.bgElevated
          else -> Color.Transparent
        },
      )
      .hoverable(interactionSource)
      .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick)
      .padding(horizontal = LemonadeTheme.spaces.spacing300, vertical = 6.dp),
  ) {
    LemonadeUi.Text(
      text = sample.code,
      textStyle = LemonadeTheme.typography.bodyXSmallSemiBold,
      color = if (selected) colors.content.contentBrand else colors.content.contentTertiary,
      modifier = Modifier.width(52.dp),
    )
    LemonadeUi.Text(
      text = sample.title,
      textStyle = LemonadeTheme.typography.bodySmallMedium,
      color = if (selected) colors.content.contentPrimary else colors.content.contentSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}
