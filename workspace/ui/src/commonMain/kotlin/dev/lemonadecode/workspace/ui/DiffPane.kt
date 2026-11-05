package dev.lemonadecode.workspace.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.teya.lemonade.Badge
import com.teya.lemonade.Button
import com.teya.lemonade.Chip
import com.teya.lemonade.ExperimentalLemonadeComponent
import com.teya.lemonade.Icon
import com.teya.lemonade.IconButton
import com.teya.lemonade.LemonadeTheme
import com.teya.lemonade.LemonadeUi
import com.teya.lemonade.SearchField
import com.teya.lemonade.SymbolContainer
import com.teya.lemonade.Tag
import com.teya.lemonade.Text
import com.teya.lemonade.core.LemonadeAssetSize
import com.teya.lemonade.core.LemonadeBadgeSize
import com.teya.lemonade.core.LemonadeButtonSize
import com.teya.lemonade.core.LemonadeButtonType
import com.teya.lemonade.core.LemonadeButtonVariant
import com.teya.lemonade.core.LemonadeIcons
import com.teya.lemonade.core.SymbolContainerShape
import com.teya.lemonade.core.SymbolContainerSize
import com.teya.lemonade.core.SymbolContainerVoice
import com.teya.lemonade.core.TagVoice
import dev.lemonadecode.platform.designsystem.AnchoredPopover
import dev.lemonadecode.platform.designsystem.HoverTooltip
import dev.lemonadecode.platform.designsystem.LemonadeCodeAnimation
import dev.lemonadecode.platform.designsystem.LemonadeCodeTheme
import dev.lemonadecode.platform.designsystem.TooltipPlacement
import dev.lemonadecode.platform.designsystem.bottomHairline
import dev.lemonadecode.platform.localization.resolve
import dev.lemonadecode.workspace.strings.Res
import dev.lemonadecode.workspace.strings.diff_changes
import dev.lemonadecode.workspace.strings.diff_compare_aria
import dev.lemonadecode.workspace.strings.diff_compare_with
import dev.lemonadecode.workspace.strings.diff_files
import dev.lemonadecode.workspace.strings.diff_files_hide
import dev.lemonadecode.workspace.strings.diff_files_show
import dev.lemonadecode.workspace.strings.diff_find_branch
import dev.lemonadecode.workspace.strings.diff_no_branch_match
import dev.lemonadecode.workspace.strings.diff_refresh
import dev.lemonadecode.workspace.strings.diff_vs
import dev.lemonadecode.workspace.strings.file_added
import dev.lemonadecode.workspace.strings.file_collapse
import dev.lemonadecode.workspace.strings.file_comment_count
import dev.lemonadecode.workspace.strings.file_copy_path
import dev.lemonadecode.workspace.strings.file_deleted
import dev.lemonadecode.workspace.strings.file_expand
import dev.lemonadecode.workspace.strings.file_full
import dev.lemonadecode.workspace.strings.file_modified
import dev.lemonadecode.workspace.strings.file_preview
import dev.lemonadecode.workspace.strings.file_renamed
import dev.lemonadecode.workspace.ui.preview.FilePreviewBody
import dev.lemonadecode.workspace.uimodel.BaseBranchModel
import dev.lemonadecode.workspace.uimodel.DiffBaseModel
import dev.lemonadecode.workspace.uimodel.DiffFileModel
import dev.lemonadecode.workspace.uimodel.DiffReviewModel
import dev.lemonadecode.workspace.uimodel.DiffRowModel
import dev.lemonadecode.workspace.uimodel.DiffToolbarModel
import dev.lemonadecode.workspace.uimodel.FileChangeKind
import dev.lemonadecode.workspace.uimodel.WorkspaceEvent
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DiffPane(
  review: DiffReviewModel,
  narrowLayout: Boolean,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()
  val fileListShown = review.fileList ?: !narrowLayout
  // Here rather than in the rows, which are only composed while on screen.
  val fileStates = remember { HashMap<String, DiffFileRowsState>() }
  SideEffect { fileStates.keys.retainAll(review.files.mapTo(HashSet()) { it.id }) }
  val measure = rememberCodeMeasure()
  RevealOpenComposer(review = review, listState = listState)
  // Where each file's header sits in the list below, to follow the scroll and to jump to a file.
  val headers = remember(review.files) { review.headerItemIndices() }
  val activeFile by remember(headers, review.files) {
    derivedStateOf {
      when {
        review.files.isEmpty() -> null
        !listState.canScrollForward && listState.canScrollBackward -> review.files.last().id
        // A gap at the top already belongs to the file below it.
        else -> review.files[headers.indexOfLast { it <= listState.firstVisibleItemIndex + 1 }.coerceAtLeast(0)].id
      }
    }
  }
  Column(modifier = modifier) {
    DiffToolbar(
      toolbar = review.toolbar,
      onEvent = onEvent,
      fileList = FileListToggle(shown = fileListShown, onToggle = { onEvent(WorkspaceEvent.ShowFileList(!fileListShown)) }),
    )
    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
      // Slides out from the left edge as it widens, pushing the diff over.
      AnimatedVisibility(
        visible = fileListShown,
        enter = LemonadeCodeAnimation.expandSideways(from = Alignment.End),
        exit = LemonadeCodeAnimation.shrinkSideways(towards = Alignment.End),
      ) {
        ChangedFiles(
          files = review.files,
          activeFile = activeFile,
          onPick = { file ->
            if (!file.expanded) onEvent(WorkspaceEvent.RevealFile(file.id))
            // Opening a file only adds items below its header, so the header's place holds.
            val header = headers[review.files.indexOf(file)]
            scope.launch { listState.animateScrollToItem(header) }
          },
          modifier = Modifier.width(FileListWidth).fillMaxHeight(),
        )
      }
      LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = spaces.spacing400, end = spaces.spacing400, bottom = spaces.spacing400),
        modifier = Modifier.weight(1f).fillMaxHeight(),
      ) {
        review.files.forEachIndexed { index, file ->
          if (index > 0) {
            item(key = "gap:${file.id}") { Spacer(modifier = diffItemMotion().height(spaces.spacing300)) }
          }
          stickyHeader(key = "head:${file.id}") {
            DiffFileHeader(file = file, onEvent = onEvent, modifier = diffItemMotion())
          }
          val preview = file.preview
          when {
            !file.expanded -> Unit
            preview != null && preview.showing -> item(key = "body:${file.id}") {
              FilePreviewBody(file = file, preview = preview, assets = review.assets, onEvent = onEvent, modifier = diffItemMotion())
            }
            else -> diffFileRows(file = file, state = fileStates.getOrPut(file.id) { DiffFileRowsState() }, measure = measure, onEvent = onEvent)
          }
        }
      }
    }
    SendBar(sendBar = review.sendBar, onEvent = onEvent)
  }
}

/**
 * The composer brings itself into view as it opens. One already open as the diff appears can be on
 * a line far down that the list hasn't composed, so the list first brings that line up from below
 * the bottom edge, and the composer then shows itself from there as it would have.
 */
@Composable
private fun RevealOpenComposer(
  review: DiffReviewModel,
  listState: LazyListState,
) {
  val lineHeight = with(LocalDensity.current) { 20.dp.toPx() }
  LaunchedEffect(listState) {
    val index = review.composerItemIndex() ?: return@LaunchedEffect
    withFrameNanos { }
    val layout = listState.layoutInfo
    if (layout.visibleItemsInfo.any { it.index == index }) return@LaunchedEffect
    listState.scrollToItem(index)
    val _ = listState.scrollBy(-(layout.viewportEndOffset - layout.viewportStartOffset - lineHeight))
  }
}

/** The list item of the line the composer is open under, counted as [DiffPane] lays the items out. */
private fun DiffReviewModel.composerItemIndex(): Int? {
  var item = 0
  files.forEachIndexed { index, file ->
    if (index > 0) item++ // The gap above the file.
    item++ // Its header.
    if (file.preview?.showing != true) {
      val row = file.rows.indexOfFirst { it is DiffRowModel.Line && it.composer != null }
      if (row >= 0) return item + row
    }
    item += file.bodyItemCount
  }
  return null
}

/** Where each file's header sits in [DiffPane]'s list, counted the way it lays the items out. */
private fun DiffReviewModel.headerItemIndices(): IntArray {
  var item = 0
  return IntArray(files.size) { index ->
    if (index > 0) item++ // The gap above the file.
    val header = item
    item += 1 + files[index].bodyItemCount
    header
  }
}

/** The button that shows or hides the changed files beside the diff. */
internal class FileListToggle(
  val shown: Boolean,
  val onToggle: () -> Unit,
)

/** "Changes · working tree vs <branch>", the branch a picker; the summary once there is a diff. */
@Composable
internal fun DiffToolbar(
  toolbar: DiffToolbarModel,
  onEvent: (WorkspaceEvent) -> Unit,
  fileList: FileListToggle? = null,
) {
  val spaces = LemonadeTheme.spaces
  val secondary = LemonadeTheme.colors.content.contentSecondary
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing300),
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = if (fileList != null) spaces.spacing400 else spaces.spacing600, end = spaces.spacing400, top = spaces.spacing300, bottom = spaces.spacing300),
  ) {
    fileList?.let { toggle ->
      val label = stringResource(if (toggle.shown) Res.string.diff_files_hide else Res.string.diff_files_show)
      HoverTooltip(text = label, placement = TooltipPlacement.BelowStart) {
        LemonadeUi.IconButton(
          icon = LemonadeIcons.List,
          contentDescription = label,
          onClick = toggle.onToggle,
          variant = LemonadeButtonVariant.Neutral,
          type = if (toggle.shown) LemonadeButtonType.Solid else LemonadeButtonType.Ghost,
          size = LemonadeButtonSize.XSmall,
        )
      }
    }
    LemonadeUi.Text(text = stringResource(Res.string.diff_changes), textStyle = LemonadeTheme.typography.headingXXSmall)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    ) {
      LemonadeUi.Tag(label = toolbar.sourceLabel.resolve(), voice = TagVoice.Neutral)
      LemonadeUi.Text(text = stringResource(Res.string.diff_vs), textStyle = LemonadeTheme.typography.bodySmallRegular, color = secondary)
      DiffBasePicker(base = toolbar.base, onEvent = onEvent)
    }
    LemonadeUi.Text(
      text = toolbar.summary?.resolve().orEmpty(),
      textStyle = LemonadeTheme.typography.bodySmallRegular,
      color = secondary,
      maxLines = 1,
      modifier = Modifier.weight(1f),
    )
    LemonadeUi.IconButton(
      icon = LemonadeIcons.ArrowRotateCw,
      contentDescription = stringResource(Res.string.diff_refresh),
      onClick = { onEvent(WorkspaceEvent.RefreshDiff) },
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.XSmall,
    )
  }
}

/** The branch the diff compares with; opens a searchable list of the others. */
@OptIn(ExperimentalLemonadeComponent::class)
@Composable
private fun DiffBasePicker(
  base: DiffBaseModel,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val colors = LemonadeTheme.colors
  val spaces = LemonadeTheme.spaces
  val selected = base.selected ?: "…"
  val pickerLabel = stringResource(Res.string.diff_compare_aria, selected)
  Box {
    LemonadeUi.Button(
      label = selected,
      onClick = { onEvent(WorkspaceEvent.ToggleDiffBasePicker) },
      trailingIcon = LemonadeIcons.ChevronDown,
      variant = LemonadeButtonVariant.Neutral,
      type = if (base.pickerOpen) LemonadeButtonType.Solid else LemonadeButtonType.Subtle,
      size = LemonadeButtonSize.XSmall,
      enabled = base.options.isNotEmpty(),
      modifier = Modifier.semantics { contentDescription = pickerLabel },
    )
    AnchoredPopover(
      visible = base.pickerOpen,
      onDismissRequest = { onEvent(WorkspaceEvent.DismissDiffBasePicker) },
      width = BasePickerWidth,
      placement = TooltipPlacement.BelowStart,
      padding = spaces.spacing300,
      spacing = spaces.spacing200,
    ) {
      // Inside, so each opening starts with an empty search.
      var query by remember { mutableStateOf("") }
      val shown = base.options.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
      LemonadeUi.Text(
        text = stringResource(Res.string.diff_compare_with),
        textStyle = LemonadeTheme.typography.bodySmallSemiBold,
        modifier = Modifier.padding(start = spaces.spacing200, end = spaces.spacing200, top = spaces.spacing100),
      )
      LemonadeUi.SearchField(
        input = query,
        onInputChanged = { query = it },
        placeholder = stringResource(Res.string.diff_find_branch),
        modifier = Modifier.fillMaxWidth(),
      )
      Column(
        modifier = Modifier
          .heightIn(max = BasePickerMaxHeight)
          .verticalScroll(rememberScrollState()),
      ) {
        shown.forEach { branch ->
          BaseOption(branch = branch, selected = branch.name == base.selected, onClick = { onEvent(WorkspaceEvent.CompareWith(branch.name)) })
        }
        if (shown.isEmpty()) {
          LemonadeUi.Text(
            text = stringResource(Res.string.diff_no_branch_match),
            textStyle = LemonadeTheme.typography.bodySmallRegular,
            color = colors.content.contentSecondary,
            modifier = Modifier.padding(horizontal = spaces.spacing200, vertical = spaces.spacing300),
          )
        }
      }
    }
  }
}

/** One branch in the picker: its name, when it last changed, and a tick when it's the one compared with. */
@Composable
private fun BaseOption(
  branch: BaseBranchModel,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val colors = LemonadeTheme.colors
  HoverRow(onClick = onClick, role = Role.RadioButton, modifier = Modifier.semantics { this.selected = selected }) {
    Column(modifier = Modifier.weight(1f)) {
      LemonadeUi.Text(text = branch.name, textStyle = LemonadeCodeTheme.typography.code.copy(color = colors.content.contentPrimary))
      branch.detail?.let { detail ->
        LemonadeUi.Text(text = detail.resolve(), textStyle = LemonadeTheme.typography.bodyXSmallRegular, color = colors.content.contentSecondary, maxLines = 1)
      }
    }
    if (selected) {
      LemonadeUi.Icon(icon = LemonadeIcons.CircleCheck, contentDescription = null, size = LemonadeAssetSize.Small, tint = colors.content.contentBrand)
    }
  }
}

private val BasePickerWidth = 340.dp
private val BasePickerMaxHeight = 300.dp
private val FileListWidth = 248.dp

/**
 * The changed files beside the diff; the one in view is lit, and picking one scrolls the diff to it.
 * The names aren't selectable here, since a drag to select would take the press that picks the
 * file; the path in each file's header can be selected and copied instead.
 */
@Composable
private fun ChangedFiles(
  files: List<DiffFileModel>,
  activeFile: String?,
  onPick: (DiffFileModel) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val label = stringResource(Res.string.diff_files)
  Column(
    verticalArrangement = Arrangement.spacedBy(2.dp),
    modifier = modifier
      .semantics { contentDescription = label }
      .verticalScroll(rememberScrollState())
      .padding(start = spaces.spacing400, bottom = spaces.spacing400),
  ) {
    files.forEach { file ->
      val changeName = changeName(file.change)
      HoverRow(
        onClick = { onPick(file) },
        minHeight = 44.dp,
        selectedBackground = file.id == activeFile,
        modifier = Modifier.semantics { selected = file.id == activeFile },
      ) {
        LemonadeUi.SymbolContainer(
          text = changeName.take(1).uppercase(),
          voice = changeVoice(file.change),
          size = SymbolContainerSize.XSmall,
          shape = SymbolContainerShape.Rounded,
          modifier = Modifier.semantics { contentDescription = changeName },
        )
        Column(modifier = Modifier.weight(1f)) {
          LemonadeUi.Text(text = file.fileName, textStyle = LemonadeTheme.typography.bodySmallMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
          if (file.directory.isNotEmpty()) {
            LemonadeUi.Text(
              text = file.directory.removeSuffix("/"),
              textStyle = LemonadeTheme.typography.bodyXSmallRegular,
              color = colors.content.contentSecondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
        if (file.commentCount > 0) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            LemonadeUi.Icon(icon = LemonadeIcons.Bubble, contentDescription = null, size = LemonadeAssetSize.XSmall, tint = colors.content.contentSecondary)
            LemonadeUi.Text(text = file.commentCount.toString(), textStyle = LemonadeTheme.typography.bodyXSmallSemiBold, color = colors.content.contentSecondary)
          }
        }
      }
    }
  }
}

@Composable
private fun changeName(change: FileChangeKind): String = when (change) {
  FileChangeKind.Added -> stringResource(Res.string.file_added)
  FileChangeKind.Modified -> stringResource(Res.string.file_modified)
  FileChangeKind.Deleted -> stringResource(Res.string.file_deleted)
  FileChangeKind.Renamed -> stringResource(Res.string.file_renamed)
}

private fun changeVoice(change: FileChangeKind): SymbolContainerVoice = when (change) {
  FileChangeKind.Added -> SymbolContainerVoice.Positive
  FileChangeKind.Modified -> SymbolContainerVoice.Info
  FileChangeKind.Deleted -> SymbolContainerVoice.Critical
  FileChangeKind.Renamed -> SymbolContainerVoice.Warning
}

@Composable
private fun DiffFileHeader(
  file: DiffFileModel,
  onEvent: (WorkspaceEvent) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spaces = LemonadeTheme.spaces
  val colors = LemonadeTheme.colors
  val radius = LemonadeTheme.radius.radius500
  val shape = if (file.expanded) RoundedCornerShape(topStart = radius, topEnd = radius) else RoundedCornerShape(radius)
  val changeName = changeName(file.change)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spaces.spacing200),
    modifier = modifier
      .fillMaxWidth()
      .background(colors.background.bgSubtle, shape)
      .then(if (file.expanded) Modifier.bottomHairline(colors.border.borderNeutralLow) else Modifier)
      .padding(start = spaces.spacing200, end = spaces.spacing300, top = spaces.spacing200, bottom = spaces.spacing200),
  ) {
    LemonadeUi.IconButton(
      icon = if (file.expanded) LemonadeIcons.ChevronDown else LemonadeIcons.ChevronRight,
      contentDescription = stringResource(if (file.expanded) Res.string.file_collapse else Res.string.file_expand),
      onClick = { onEvent(WorkspaceEvent.ToggleFileExpanded(file.id)) },
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.XSmall,
    )
    LemonadeUi.SymbolContainer(
      text = changeName.take(1).uppercase(),
      voice = changeVoice(file.change),
      size = SymbolContainerSize.XSmall,
      shape = SymbolContainerShape.Rounded,
      modifier = Modifier.semantics { contentDescription = changeName },
    )
    // Only the path can be selected: a drag over it selects text and ⌘C (Ctrl+C) copies it,
    // while the header's buttons and chips still answer only to their own clicks.
    SelectionContainer {
      LemonadeUi.Text(
        text = buildAnnotatedString {
          withStyle(SpanStyle(color = colors.content.contentSecondary)) { append(file.directory) }
          withStyle(SpanStyle(color = colors.content.contentPrimary, fontWeight = FontWeight.SemiBold)) {
            append(file.fileName)
          }
        },
        textStyle = LemonadeTheme.typography.bodySmallRegular,
        maxLines = 1,
      )
    }
    CopyPathButton(path = file.path, onEvent = onEvent)
    LemonadeUi.Tag(label = file.language, voice = TagVoice.Neutral)
    if (file.commentCount > 0) {
      val commentCountLabel = pluralStringResource(Res.plurals.file_comment_count, file.commentCount, file.commentCount)
      LemonadeUi.Badge(
        text = file.commentCount.toString(),
        size = LemonadeBadgeSize.Small,
        modifier = Modifier.semantics { contentDescription = commentCountLabel },
      )
    }
    Spacer(modifier = Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(spaces.spacing200)) {
      if (file.additions > 0) {
        LemonadeUi.Text(
          text = "+${file.additions}",
          textStyle = LemonadeTheme.typography.bodySmallSemiBold,
          color = colors.content.contentPositive,
        )
      }
      if (file.deletions > 0) {
        LemonadeUi.Text(
          text = "−${file.deletions}",
          textStyle = LemonadeTheme.typography.bodySmallSemiBold,
          color = colors.content.contentCritical,
        )
      }
    }
    file.preview?.let { preview ->
      LemonadeUi.Chip(
        label = stringResource(Res.string.file_preview),
        selected = preview.showing,
        leadingIcon = LemonadeIcons.EyeOpen,
        onChipClicked = { onEvent(WorkspaceEvent.ToggleFilePreview(file.id)) },
      )
    }
    if (file.canShowFullFile) {
      LemonadeUi.Chip(
        label = stringResource(Res.string.file_full),
        selected = file.showingFullFile,
        leadingIcon = LemonadeIcons.FileText,
        onChipClicked = { onEvent(WorkspaceEvent.ToggleFullFile(file.id)) },
      )
    }
  }
}

/** Puts a file's path on the clipboard, as Copy folder path does for a session, and says so. */
@Composable
private fun CopyPathButton(
  path: String,
  onEvent: (WorkspaceEvent) -> Unit,
) {
  val clipboard = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val label = stringResource(Res.string.file_copy_path)
  HoverTooltip(text = label, placement = TooltipPlacement.BelowStart) {
    LemonadeUi.IconButton(
      icon = LemonadeIcons.Copy,
      contentDescription = label,
      onClick = {
        scope.launch {
          val _ = runCatching { clipboard.setClipEntry(plainTextClipEntry(path)) }
          onEvent(WorkspaceEvent.CopiedFilePath(path))
        }
      },
      variant = LemonadeButtonVariant.Neutral,
      type = LemonadeButtonType.Ghost,
      size = LemonadeButtonSize.XSmall,
    )
  }
}
