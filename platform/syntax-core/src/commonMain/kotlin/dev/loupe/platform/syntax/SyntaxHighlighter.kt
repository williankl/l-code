package dev.loupe.platform.syntax

public enum class SyntaxKind { Plain, Keyword, String, Number, Function, Type, Comment, Punctuation, Heading }

public data class SyntaxToken(
  val text: String,
  val kind: SyntaxKind,
)

/** A language the highlighter knows, picked from the file name. */
public class SyntaxLanguage internal constructor(
  public val displayName: String,
  internal val grammar: Grammar,
) {
  internal fun copy(displayName: String): SyntaxLanguage = SyntaxLanguage(displayName, grammar)
}

/**
 * Lightweight, line-oriented colouring for code review: keywords, strings, numbers, comments,
 * calls and type-like names. Block comments carry across lines. It is not a parser, and it
 * doesn't try to be one.
 */
public object SyntaxHighlighter {
  public fun languageOf(path: String): SyntaxLanguage {
    val name = path.substringAfterLast('/')
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return byFileName[name] ?: byExtension[extension] ?: PlainText
  }

  public fun highlight(lines: List<String>, language: SyntaxLanguage): List<List<SyntaxToken>> {
    var inBlockComment = false
    return lines.map { line ->
      when (val grammar = language.grammar) {
        Grammar.Markdown -> markdownLine(line)
        Grammar.None -> if (line.isEmpty()) emptyList() else listOf(SyntaxToken(line, SyntaxKind.Plain))
        is Grammar.Code -> {
          val (tokens, stillInBlock) = codeLine(line, inBlockComment, grammar)
          inBlockComment = stillInBlock
          tokens
        }
      }
    }
  }

  private val PlainText = SyntaxLanguage("Text", Grammar.None)

  private val cFamilyTypes = setOf("number", "string", "boolean", "void", "any", "unknown", "never", "int", "long", "float", "double", "char", "byte", "short", "bool")

  private fun code(name: String, keywords: String, lineComments: List<String> = listOf("//"), blockComments: Boolean = true) = SyntaxLanguage(name, Grammar.Code(keywords.split(' ').toSet(), cFamilyTypes, lineComments, blockComments))

  private val typeScript = code(
    "TypeScript",
    "import type from const let var interface export function return async await if else new for of in true false null " +
      "undefined typeof as default class extends implements throw try catch finally switch case break continue while do enum " +
      "readonly private public protected static yield delete instanceof keyof declare namespace this super",
  )
  private val javaScript = typeScript.copy(displayName = "JavaScript")
  private val kotlin = code(
    "Kotlin",
    "package import class interface object fun val var return if else when for while do try catch finally throw true false " +
      "null this super is in as typealias data sealed enum abstract open override private public protected internal " +
      "companion suspend inline reified operator infix lateinit const by constructor init annotation value",
  )
  private val java = code(
    "Java",
    "package import class interface enum extends implements return if else for while do switch case break continue try catch " +
      "finally throw throws new true false null this super static final public private protected abstract void record var",
  )
  private val swift = code(
    "Swift",
    "import class struct enum protocol extension func var let return if else guard for in while repeat switch case break " +
      "continue throw throws try catch true false nil self super init deinit public private internal fileprivate open static " +
      "override async await some any",
  )
  private val go = code(
    "Go",
    "package import func var const type struct interface map chan return if else for range switch case default break " +
      "continue go defer select true false nil",
  )
  private val rust = code(
    "Rust",
    "use mod fn let mut const static struct enum trait impl pub crate self super return if else match for in while loop " +
      "break continue true false as ref move async await where type dyn unsafe",
  )
  private val cLike = code(
    "C",
    "include define if else for while do switch case break continue return struct union enum typedef static const extern " +
      "sizeof void unsigned signed true false NULL class public private protected namespace template typename virtual new delete",
  )
  private val python = code(
    "Python",
    "import from as def class return if elif else for in while try except finally raise with lambda yield pass break " +
      "continue True False None and or not is global nonlocal async await",
    lineComments = listOf("#"),
    blockComments = false,
  )
  private val shell = code(
    "Shell",
    "if then else elif fi for in do done while until case esac function return export local readonly echo",
    lineComments = listOf("#"),
    blockComments = false,
  )
  private val config = code("Config", "true false null yes no on off", lineComments = listOf("#"), blockComments = false)
  private val json = code("JSON", "true false null", lineComments = emptyList(), blockComments = false)
  private val gradle = kotlin.copy(displayName = "Gradle")
  private val markdown = SyntaxLanguage("Markdown", Grammar.Markdown)

  private val byExtension: Map<String, SyntaxLanguage> = buildMap {
    listOf("ts", "tsx", "mts", "cts").forEach { put(it, typeScript) }
    listOf("js", "jsx", "mjs", "cjs").forEach { put(it, javaScript) }
    listOf("kt", "kts").forEach { put(it, kotlin) }
    put("java", java)
    put("swift", swift)
    put("go", go)
    put("rs", rust)
    listOf("c", "h", "cc", "cpp", "hpp", "m", "mm").forEach { put(it, cLike) }
    put("py", python)
    listOf("sh", "bash", "zsh").forEach { put(it, shell) }
    listOf("yml", "yaml", "toml", "properties", "ini", "env").forEach { put(it, config) }
    put("json", json)
    listOf("md", "markdown").forEach { put(it, markdown) }
  }

  private val byFileName: Map<String, SyntaxLanguage> = mapOf(
    "build.gradle.kts" to gradle,
    "settings.gradle.kts" to gradle,
    "Dockerfile" to shell,
    "Makefile" to shell,
  )

  private val number = Regex("^[0-9][0-9_]*(\\.[0-9]+)?")
  private val word = Regex("^[A-Za-z_$][\\w$]*")
  private val markdownInline = Regex("(`[^`]*`)|(\\|)|(---)")

  private fun codeLine(line: String, startsInBlock: Boolean, grammar: Grammar.Code): Pair<List<SyntaxToken>, Boolean> {
    val out = TokenBuffer()
    var inBlock = startsInBlock
    var i = 0
    val n = line.length
    while (i < n) {
      if (inBlock) {
        val end = line.indexOf("*/", i)
        if (end < 0) {
          out.push(line.substring(i), SyntaxKind.Comment)
          i = n
        } else {
          out.push(line.substring(i, end + 2), SyntaxKind.Comment)
          i = end + 2
          inBlock = false
        }
        continue
      }
      val c = line[i]
      when {
        grammar.lineComments.any { line.startsWith(it, i) } -> {
          out.push(line.substring(i), SyntaxKind.Comment)
          i = n
        }
        grammar.blockComments && line.startsWith("/*", i) -> {
          val end = line.indexOf("*/", i + 2)
          if (end < 0) {
            out.push(line.substring(i), SyntaxKind.Comment)
            inBlock = true
            i = n
          } else {
            out.push(line.substring(i, end + 2), SyntaxKind.Comment)
            i = end + 2
          }
        }
        c == '\'' || c == '"' || c == '`' -> {
          var j = i + 1
          while (j < n && line[j] != c) {
            if (line[j] == '\\') j++
            j++
          }
          val end = minOf(j + 1, n)
          out.push(line.substring(i, end), SyntaxKind.String)
          i = end
        }
        c.isDigit() && !isWordChar(line.getOrNull(i - 1)) -> {
          val match = number.find(line.substring(i))?.value ?: c.toString()
          out.push(match, SyntaxKind.Number)
          i += match.length
        }
        c.isLetter() || c == '_' || c == '$' -> {
          val w = word.find(line.substring(i))?.value ?: c.toString()
          var j = i + w.length
          while (j < n && line[j] == ' ') j++
          val kind = when {
            w in grammar.keywords -> SyntaxKind.Keyword
            w in grammar.types -> SyntaxKind.Type
            line.getOrNull(j) == '(' -> SyntaxKind.Function
            w.first().isUpperCase() -> SyntaxKind.Type
            else -> SyntaxKind.Plain
          }
          out.push(w, kind)
          i += w.length
        }
        c == ' ' || c == '\t' -> {
          var j = i
          while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
          out.push(line.substring(i, j), SyntaxKind.Plain)
          i = j
        }
        else -> {
          out.push(c.toString(), SyntaxKind.Punctuation)
          i++
        }
      }
    }
    return out.tokens to inBlock
  }

  private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_' || c == '$')

  private fun markdownLine(line: String): List<SyntaxToken> {
    if (line.startsWith("#")) return listOf(SyntaxToken(line, SyntaxKind.Heading))
    if (line.startsWith("```")) return listOf(SyntaxToken(line, SyntaxKind.Punctuation))
    val out = TokenBuffer()
    var last = 0
    markdownInline.findAll(line).forEach { match ->
      if (match.range.first > last) out.push(line.substring(last, match.range.first), SyntaxKind.Plain)
      out.push(match.value, if (match.groups[1] != null) SyntaxKind.String else SyntaxKind.Punctuation)
      last = match.range.last + 1
    }
    if (last < line.length) out.push(line.substring(last), SyntaxKind.Plain)
    return out.tokens
  }

  private class TokenBuffer {
    val tokens = mutableListOf<SyntaxToken>()

    fun push(text: String, kind: SyntaxKind) {
      if (text.isEmpty()) return
      val last = tokens.lastOrNull()
      if (last != null && last.kind == kind) {
        tokens[tokens.lastIndex] = last.copy(text = last.text + text)
      } else {
        tokens += SyntaxToken(text, kind)
      }
    }
  }
}

internal sealed interface Grammar {
  data object None : Grammar

  data object Markdown : Grammar

  data class Code(
    val keywords: Set<String>,
    val types: Set<String>,
    val lineComments: List<String>,
    val blockComments: Boolean,
  ) : Grammar
}
