package dev.lemonadecode.workspace.uimodel

import androidx.compose.runtime.Immutable

@Immutable
public data class TerminalTranscriptModel(
  val lines: List<TerminalLineModel>,
)

@Immutable
public sealed interface TerminalLineModel {
  /** Centered label between two rules, e.g. "Sent from diff review · 3 comments". */
  @Immutable
  public data class Rule(val text: String) : TerminalLineModel

  @Immutable
  public data class WorkingDirectory(val path: String, val branch: String) : TerminalLineModel

  @Immutable
  public data class Command(val text: String) : TerminalLineModel

  @Immutable
  public data class Output(val text: String) : TerminalLineModel

  /** A message the user sent to the agent, optionally with numbered review items. */
  @Immutable
  public data class UserMessage(
    val text: String,
    val items: List<PromptItemModel> = emptyList(),
  ) : TerminalLineModel

  @Immutable
  public data class AgentMessage(val text: String) : TerminalLineModel

  @Immutable
  public data class ToolCall(
    val verb: String,
    val target: String,
    val stats: List<ToolStatModel> = emptyList(),
    val running: Boolean = false,
  ) : TerminalLineModel

  /** The live input line with a blinking block cursor; `›` for agents, `$` for shells. */
  @Immutable
  public data class InputPrompt(val symbol: String) : TerminalLineModel
}

@Immutable
public data class PromptItemModel(
  val number: String,
  val location: String,
  val text: String,
)

@Immutable
public data class ToolStatModel(
  val text: String,
  val tone: ToolStatTone,
)

public enum class ToolStatTone { Positive, Critical, Neutral }
