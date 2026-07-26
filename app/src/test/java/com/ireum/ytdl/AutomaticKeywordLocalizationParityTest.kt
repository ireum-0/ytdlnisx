package com.ireum.ytdl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AutomaticKeywordLocalizationParityTest {
    @Test
    fun automaticKeywordStringsExistInEnglishAndKoreanWithMatchingPlaceholders() {
        val root = locateProjectRoot()
        val english = readStrings(File(root, "app/src/main/res/values/strings.xml"))
            .filterKeys { it.startsWith("automatic_keyword_") }
        val korean = readStrings(File(root, "app/src/main/res/values-ko/strings.xml"))
            .filterKeys { it.startsWith("automatic_keyword_") }

        assertTrue(english.isNotEmpty())
        assertEquals(english.keys, korean.keys)
        english.forEach { (name, value) ->
            assertEquals(name, placeholders(value), placeholders(korean.getValue(name)))
        }
    }

    private fun placeholders(value: String): List<String> =
        Regex("%(?:\\d+\\$)?[a-zA-Z]").findAll(value).map { it.value }.sorted().toList()

    private fun readStrings(file: File): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        return (0 until document.getElementsByTagName("string").length).associate { index ->
            val element = document.getElementsByTagName("string").item(index)
            element.attributes.getNamedItem("name").nodeValue to element.textContent
        }
    }

    private fun locateProjectRoot(): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(4) {
            if (File(current, "app/src/main/res/values/strings.xml").isFile) return current
            current = current.parentFile ?: current
        }
        error("Could not locate Android project root")
    }
}
