package org.example.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every translation must carry exactly the placeholders of its default string, so a format call never crashes in another language. */
class LocaleStringsTest {
    private val resDir = File("").absoluteFile.resolve("src/main/res")
    private val placeholder = Regex("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]")

    private fun read(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableMapOf<String, String>()
        for (tag in listOf("string", "item")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                val name = node.attributes.getNamedItem("name")?.nodeValue
                    ?: node.attributes.getNamedItem("quantity")?.nodeValue?.let { q -> "${node.parentNode.attributes.getNamedItem("name").nodeValue}#$q" }
                    ?: continue
                out[name] = node.textContent
            }
        }
        return out
    }

    private fun placeholders(text: String): List<String> = placeholder.findAll(text).map { it.value }.filter { it != "%%" }.sorted().toList()

    private fun translations(): List<File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }.orEmpty()
            .map { File(it, "strings.xml") }.filter { it.exists() }

    @Test
    fun defaultStringsWithSeveralArgumentsUsePositionalPlaceholders() {
        val bad = read(File(resDir, "values/strings.xml")).filter { (_, text) ->
            val found = placeholders(text)
            found.size > 1 && found.any { !it.contains('$') }
        }.keys
        assertEquals(emptySet(), bad)
    }

    @Test
    fun everyTranslationKeepsThePlaceholdersOfTheDefault() {
        val default = read(File(resDir, "values/strings.xml"))
        val problems = translations().flatMap { file ->
            read(file).mapNotNull { (name, text) ->
                val original = default[name] ?: return@mapNotNull "${file.parentFile.name}: $name has no default"
                if (placeholders(text) == placeholders(original)) null
                else "${file.parentFile.name}: $name ${placeholders(text)} != ${placeholders(original)}"
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun placeholderComparisonCatchesMismatches() {
        assertTrue(placeholders("%1\$s sent %2\$d") != placeholders("%1\$s sent"))
        assertEquals(placeholders("%2\$d of %1\$s"), placeholders("%1\$s of %2\$d"))
        assertEquals(emptyList(), placeholders("100%%"))
    }
}
