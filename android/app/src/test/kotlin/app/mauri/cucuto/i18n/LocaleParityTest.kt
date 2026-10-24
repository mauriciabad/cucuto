package app.mauri.cucuto.i18n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every user-visible string ships in English and Spanish (#107, ADR 0036). Lint's MissingTranslation catches the
 * same gaps, but only in the slower Lint job; this test fails `./gradlew check` too, and also compares placeholders
 * and plural quantities. It scans every `src/<set>/res/values` folder of the Android project, so a module that gains
 * strings later is covered without touching this file.
 */
class LocaleParityTest {
    private val androidRoot = File("..").canonicalFile

    @Test
    fun everyStringHasASpanishTranslationAndNothingElse() {
        val problems = mutableListOf<String>()
        val folders = valuesFolders()
        assertTrue("no values/ folders found under $androidRoot", folders.isNotEmpty())

        for (values in folders) {
            val english = readResources(values)
            if (english.isEmpty()) continue
            val where = values.parentFile.relativeTo(androidRoot).path
            val spanishFolder = File(values.parentFile, "values-es")
            val spanish = readResources(spanishFolder)

            for ((name, en) in english) {
                val es = spanish[name]
                when {
                    !en.translatable && es != null -> {
                        problems +=
                            "$where: $name is translatable=\"false\" but has a Spanish entry"
                    }

                    !en.translatable -> {
                        Unit
                    }

                    es == null -> {
                        problems += "$where: $name has no Spanish entry in values-es/"
                    }

                    else -> {
                        problems += compare(where, name, en, es)
                    }
                }
            }
            for (name in spanish.keys - english.keys) {
                problems += "$where: values-es/ has $name, which values/ no longer has"
            }
        }

        assertTrue(
            "Spanish translations are out of step with English (every user-visible string needs English and " +
                "Spanish, see docs/decisions/0036-app-languages.md):\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    private fun compare(
        where: String,
        name: String,
        en: Resource,
        es: Resource,
    ): List<String> {
        if (en.kind != es.kind) return listOf("$where: $name is a ${en.kind} in English but a ${es.kind} in Spanish")
        val problems = mutableListOf<String>()
        val enPlaceholders =
            en.items.values
                .flatMap(::placeholders)
                .toSet()
        for ((quantity, text) in es.items) {
            val extra = placeholders(text) - enPlaceholders
            if (extra.isNotEmpty()) problems += "$where: $name[$quantity] has placeholders English lacks: $extra"
        }
        val enOther = en.items["other"]?.let(::placeholders)
        val esOther = es.items["other"]?.let(::placeholders)
        if (enOther != esOther) problems += "$where: $name has placeholders $esOther in Spanish, $enOther in English"
        if (en.kind == "plurals") {
            val missing = SPANISH_QUANTITIES - es.items.keys
            if (missing.isNotEmpty()) problems += "$where: $name lacks Spanish plural quantities $missing"
        }
        return problems
    }

    private fun valuesFolders(): List<File> =
        androidRoot
            .walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isDirectory && it.name == "values" && it.parentFile.name == "res" }
            .filter { it.parentFile.parentFile.parentFile.name == "src" }
            .sortedBy { it.path }
            .toList()

    private fun readResources(folder: File): Map<String, Resource> {
        val files = folder.listFiles { file -> file.extension == "xml" }.orEmpty()
        val resources = mutableMapOf<String, Resource>()
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in files.sortedBy { it.name }) {
            val root = builder.parse(file).documentElement
            for (element in root.childElements()) {
                val name = element.getAttribute("name")
                val translatable = element.getAttribute("translatable") != "false"
                when (element.tagName) {
                    "string" -> {
                        resources[name] = Resource("string", translatable, mapOf("other" to element.textContent))
                    }

                    "plurals" -> {
                        resources[name] =
                            Resource(
                                "plurals",
                                translatable,
                                element.childElements().associate { it.getAttribute("quantity") to it.textContent },
                            )
                    }

                    "string-array" -> {
                        resources[name] =
                            Resource(
                                "string-array",
                                translatable,
                                element.childElements().withIndex().associate { (i, item) ->
                                    (if (i == 0) "other" else "item$i") to item.textContent
                                },
                            )
                    }
                }
            }
        }
        return resources
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun placeholders(text: String): Set<String> =
        PLACEHOLDER
            .findAll(text)
            .map { it.value }
            .filter { it != "%%" }
            .toSet()

    private data class Resource(
        val kind: String,
        val translatable: Boolean,
        val items: Map<String, String>,
    )

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")

        /** CLDR plural categories for Spanish: "many" covers round millions ("1 000 000 de packs"). */
        val SPANISH_QUANTITIES = setOf("one", "many", "other")
    }
}
