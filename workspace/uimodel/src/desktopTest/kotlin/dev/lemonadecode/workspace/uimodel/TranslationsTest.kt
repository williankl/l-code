package dev.lemonadecode.workspace.uimodel

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every language says everything English says, with the same values filled in. */
class TranslationsTest {
  private val resources = File("src/commonMain/composeResources")
  private val english = texts(File(resources, "values/strings.xml"))
  private val languages = resources.listFiles { folder -> folder.name.startsWith("values-") }.orEmpty().sortedBy { it.name }

  @Test
  fun `every language has every string and plural`() {
    assertTrue(languages.isNotEmpty())
    languages.forEach { folder ->
      val translated = texts(File(folder, "strings.xml"))
      assertEquals(english.keys.sorted(), translated.keys.sorted(), folder.name)
    }
  }

  @Test
  fun `every translation fills in the same values as the English`() {
    languages.forEach { folder ->
      texts(File(folder, "strings.xml")).forEach { (name, texts) ->
        val expected = english[name]?.flatMap(::placeholders)?.toSet() ?: return@forEach
        texts.forEach { text -> assertEquals(expected, placeholders(text), "${folder.name}: $name: $text") }
      }
    }
  }

  @Test
  fun `every translated plural has the catch-all form`() {
    languages.forEach { folder ->
      val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(folder, "strings.xml"))
      val plurals = document.getElementsByTagName("plurals")
      repeat(plurals.length) { index ->
        val plural = plurals.item(index) as Element
        val items = plural.getElementsByTagName("item")
        val quantities = List(items.length) { (items.item(it) as Element).getAttribute("quantity") }
        assertTrue("other" in quantities, "${folder.name}: ${plural.getAttribute("name")} has $quantities")
      }
    }
  }

  /** Each string's text, and each plural's texts, by name. */
  private fun texts(file: File): Map<String, List<String>> {
    val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
    val children = root.childNodes
    return List(children.length) { children.item(it) }.filterIsInstance<Element>().associate { element ->
      val texts = when (element.tagName) {
        "plurals" -> element.getElementsByTagName("item").let { items -> List(items.length) { items.item(it).textContent } }
        else -> listOf(element.textContent)
      }
      element.getAttribute("name") to texts
    }
  }

  private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[a-z]""").findAll(text).map { it.value }.toSet()
}
