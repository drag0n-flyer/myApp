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

    private val forbiddenWords = Regex("\\b(codecs?|kbps|relay(s|ed|ing)?|MLS|E2EE)\\b", RegexOption.IGNORE_CASE)

    private fun kotlinFiles(dir: File): List<File> = dir.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test
    fun forbiddenWordsAbsent() {
        assertTrue(entries().isNotEmpty())
        val forbidden = forbiddenWords
        val hits = entries().filter { (name, text) -> forbidden.containsMatchIn(text) || forbidden.containsMatchIn(name) }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun forbiddenWordsCatchPluralsAndVariants() {
        val forbidden = forbiddenWords
        for (text in listOf("codecs", "Codec", "relays", "relayed", "MLS", "E2EE", "64 kbps")) {
            assertTrue(forbidden.containsMatchIn(text), text)
        }
    }

    @Test
    fun brandOnlyInAppName() {
        val withBrand = entries().filter { (_, text) -> text.contains("[App]") }.map { it.first }.distinct()
        assertEquals(listOf("app_name"), withBrand)
    }

    /**
     * Files (other than ChatInfoScreen.kt) that name an encryption string resource, an encryption key from :core
     * (`ChatEncryption.infoKey`, `chat_info_encryption_*`), or a literal that names an encryption type.
     */
    private fun encryptionViolations(dir: File): List<String> {
        val patterns = listOf(
            Regex("R\\.string\\.\\w*encryption\\w*"),
            Regex("\\w*encryption_\\w+"),
            Regex("\\b(ChatEncryption|infoKey)\\b"),
            Regex("\"[^\"\\n]*(encrypt|end-to-end)[^\"\\n]*\"", RegexOption.IGNORE_CASE),
        )
        return kotlinFiles(dir).filter { it.name != "ChatInfoScreen.kt" }
            .filter { f -> f.readText().let { text -> patterns.any { it.containsMatchIn(text) } } }
            .map { it.name }
    }

    @Test
    fun encryptionStringsOnlyInChatInfo() {
        assertEquals(emptyList(), encryptionViolations(File(appDir, "src/main/kotlin")))
    }

    @Test
    fun encryptionGuardCatchesPlantedReferences() {
        val scratch = kotlin.io.path.createTempDirectory("encryption-guard").toFile()
        try {
            val plants = mapOf(
                "ChatRow.kt" to "val t = stringResource(R.string.chat_info_encryption_end_to_end)",
                "ChatScreen.kt" to "Text(chat.encryption.infoKey)",
                "CallScreen.kt" to "Text(\"End-to-end encrypted\")",
                "Other.kt" to "val k = \"chat_info_encryption_server_readable\"",
            )
            for ((name, body) in plants) File(scratch, name).writeText("package x\n$body\n")
            File(scratch, "ChatInfoScreen.kt").writeText("package x\nval ok = R.string.chat_info_encryption_end_to_end\n")
            File(scratch, "Clean.kt").writeText("package x\nval ok = R.string.tab_chats\n")
            assertEquals(plants.keys, encryptionViolations(scratch).toSet())
        } finally {
            scratch.deleteRecursively()
        }
    }

    @Test
    fun everyKeyCoreCanEmitIsDefined() {
        val key = Regex("\"([a-z]+(?:_[a-z0-9]+)+)\"")
        val emitted = kotlinFiles(coreSrc).flatMap { f -> key.findAll(f.readText()).map { it.groupValues[1] } }.toSortedSet()
        assertTrue(emitted.size >= 40, "expected to find the core keys, found ${emitted.size}")
        val defined = entries().map { it.first }.toSet()
        assertEquals(emptyList(), emitted.filter { it !in defined })
    }

    /** Source lines that build a display string for data amounts or the call clock. */
    private fun coreDisplayStringHits(dir: File): List<String> {
        val patterns = listOf(
            Regex("\"[^\"\\n]*(\\bKB\\b|\\bMB\\b|≈|per 10 min|\\bused\\b)[^\"\\n]*\""),
            Regex("\\b(elapsedText|dataUsedText)\\b"),
            Regex("fun (used|estimate)\\("),
        )
        return kotlinFiles(dir).filter { f -> f.readText().let { text -> patterns.any { it.containsMatchIn(text) } } }.map { it.name }
    }

    @Test
    fun coreReturnsNoDisplayStringsForDataOrClock() {
        assertEquals(emptyList(), coreDisplayStringHits(coreSrc))
    }

    @Test
    fun coreDisplayStringGuardCatchesPlantedStrings() {
        val scratch = kotlin.io.path.createTempDirectory("core-text-guard").toFile()
        try {
            val plants = mapOf(
                "A.kt" to "val t = \"${'$'}n MB\"",
                "B.kt" to "val t = \"≈ x\"",
                "C.kt" to "val t = \"~2 MB per 10 min\"",
                "D.kt" to "val elapsedText: String = \"\"",
            )
            for ((name, body) in plants) File(scratch, name).writeText("package x\n$body\n")
            File(scratch, "Clean.kt").writeText("package x\nval ok = \"mode_ultra_low\"\n")
            assertEquals(plants.keys, coreDisplayStringHits(scratch).toSet())
        } finally {
            scratch.deleteRecursively()
        }
    }
}
