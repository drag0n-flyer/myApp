package org.example.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plain JVM checks over resource and source files. Gradle runs unit tests with the module directory as cwd. */
class StringsRulesTest {
    private val appDir = File("").absoluteFile
    private val resDir = File(appDir, "src/main/res")
    private val coreSrc = File(appDir, "../core/src/main/kotlin")

    private fun stringFiles(): List<File> =
        resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .map { File(it, "strings.xml") }.filter { it.exists() }

    /** name to text, over every `values*` directory. */
    private fun entries(): List<Pair<String, String>> = stringFiles().flatMap { file ->
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) }.map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    private fun kotlinFiles(dir: File): List<File> = dir.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun forbiddenWordsAbsent() {
        assertTrue(entries().isNotEmpty())
        val forbidden = Regex("\\b(codec|kbps|relay|MLS|E2EE)\\b", RegexOption.IGNORE_CASE)
        val hits = entries().filter { (name, text) -> forbidden.containsMatchIn(text) || forbidden.containsMatchIn(name) }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun brandOnlyInAppName() {
        val withBrand = entries().filter { (_, text) -> text.contains("[App]") }.map { it.first }.distinct()
        assertEquals(listOf("app_name"), withBrand)
    }

    @Test
    fun encryptionStringsOnlyInChatInfo() {
        val roots = listOf(File(appDir, "src/main/kotlin"), File(appDir, "src/test/kotlin"))
        val offenders = roots.flatMap { kotlinFiles(it) }
            .filter { it.name != "ChatInfoScreen.kt" && it.name != "StringsRulesTest.kt" }
            .filter { it.readText().contains("R.string.encryption_") }
            .map { it.name }
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun everyKeyCoreCanEmitIsDefined() {
        val key = Regex("\"([a-z]+(?:_[a-z0-9]+)+)\"")
        val emitted = kotlinFiles(coreSrc).flatMap { f -> key.findAll(f.readText()).map { it.groupValues[1] } }.toSortedSet()
        assertTrue(emitted.size >= 40, "expected to find the core keys, found ${emitted.size}")
        val defined = entries().map { it.first }.toSet()
        assertEquals(emptyList(), emitted.filter { it !in defined })
    }
}
