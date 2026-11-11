// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Makes the Android string resources in `l10n/res` usable from shared code (see
 * docs/adr/0003-strings-format.md). The XML stays the only source; nothing is copied by hand.
 *
 * Outputs:
 * - `commonMain`: `Res.string.<name>` and `Res.plurals.<name>` keys, used with the shared
 *   `stringResource` and `pluralStringResource` functions.
 * - `androidMain`: the mapping from those keys to `R.string` and `R.plurals`, so Android keeps
 *   reading its own resources (locale matching, plural rules and formatting are unchanged).
 * - desktop resources: one `.properties` table per locale (`strings.properties` for English,
 *   `strings_pt_BR.properties` and so on), with Android's escapes and quoting already resolved.
 *   Plurals are stored as `<name>.<quantity>`. `locales.txt` lists the translated locales.
 */
@CacheableTask
abstract class GenerateStringsTask : DefaultTask() {
    /** The `res` folder holding `values/strings.xml` and one `values-<locale>/strings.xml` per translation. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resDirectory: DirectoryProperty

    /** Kotlin package of the generated keys and of the desktop tables. */
    @get:Input
    abstract val packageName: Property<String>

    /** Package of the Android module's `R` class. */
    @get:Input
    abstract val androidNamespace: Property<String>

    @get:OutputDirectory
    abstract val commonOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val androidOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val desktopOutputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val res = resDirectory.get().asFile
        val source = File(res, "values/strings.xml")
        if (!source.isFile) throw GradleException("Missing the English strings: $source")
        val english = parse(source)
        val translations =
            res
                .listFiles { file -> file.isDirectory && file.name.startsWith("values-") }
                .orEmpty()
                .sortedBy { it.name }
                .mapNotNull { folder ->
                    val file = File(folder, "strings.xml").takeIf(File::isFile) ?: return@mapNotNull null
                    bundleSuffix(folder.name) to parse(file)
                }

        writeKeys(english)
        writeAndroidIds(english)
        writeDesktopTables(english, translations)
    }

    /** Strings and plurals of one `strings.xml`, in file order. */
    private class Table(
        val strings: LinkedHashMap<String, String>,
        val plurals: LinkedHashMap<String, Map<String, String>>,
    )

    private fun parse(file: File): Table {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        for (element in root.childElements()) {
            val name = element.getAttribute("name")
            if (!NAME.matches(name)) throw GradleException("$file: invalid resource name \"$name\"")
            when (element.tagName) {
                "string" -> {
                    strings[name] = androidText(element, file)
                }

                "plurals" -> {
                    plurals[name] =
                        element.childElements().filter { it.tagName == "item" }.associate { item ->
                            item.getAttribute("quantity") to androidText(item, file)
                        }
                }

                else -> {
                    throw GradleException("$file: <${element.tagName}> is not supported; use <string> or <plurals>.")
                }
            }
        }
        return Table(strings, plurals)
    }

    private fun writeKeys(english: Table) {
        val code = StringBuilder()
        code.appendLine("// Generated from l10n/res by the generateStrings task. Do not edit.")
        code.appendLine("package ${packageName.get()}")
        code.appendLine()
        code.appendLine("/** The interface strings of `l10n/res`, named as in the Android resources. */")
        code.appendLine("object Res {")
        code.appendLine("    /** `<string>` resources. */")
        code.appendLine("    @Suppress(\"ClassName\")")
        code.appendLine("    object string {")
        for ((name, text) in english.strings) {
            code.appendLine("        /** ${kdoc(text)} */")
            code.appendLine("        val $name: StringResource = StringResource(\"$name\")")
        }
        code.appendLine("    }")
        code.appendLine()
        code.appendLine("    /** `<plurals>` resources. */")
        code.appendLine("    @Suppress(\"ClassName\")")
        code.appendLine("    object plurals {")
        for ((name, items) in english.plurals) {
            code.appendLine("        /** ${kdoc(items["other"] ?: items.values.first())} */")
            code.appendLine("        val $name: PluralsResource = PluralsResource(\"$name\")")
        }
        code.appendLine("    }")
        code.appendLine("}")
        write(commonOutputDirectory, "Res.kt", code)
    }

    private fun writeAndroidIds(english: Table) {
        val r = "${androidNamespace.get()}.R"
        val code = StringBuilder()
        code.appendLine("// Generated from l10n/res by the generateStrings task. Do not edit.")
        code.appendLine("package ${packageName.get()}")
        code.appendLine()
        code.appendLine("/** The Android resource id of a string key. */")
        code.appendLine("internal fun androidStringId(key: String): Int =")
        code.appendLine("    when (key) {")
        for (name in english.strings.keys) code.appendLine("        \"$name\" -> $r.string.$name")
        code.appendLine("        else -> throw IllegalArgumentException(\"Unknown string: \$key\")")
        code.appendLine("    }")
        code.appendLine()
        code.appendLine("/** The Android resource id of a plurals key. */")
        code.appendLine("internal fun androidPluralsId(key: String): Int =")
        code.appendLine("    when (key) {")
        for (name in english.plurals.keys) code.appendLine("        \"$name\" -> $r.plurals.$name")
        code.appendLine("        else -> throw IllegalArgumentException(\"Unknown plurals: \$key\")")
        code.appendLine("    }")
        write(androidOutputDirectory, "AndroidStringIds.kt", code)
    }

    private fun writeDesktopTables(
        english: Table,
        translations: List<Pair<String, Table>>,
    ) {
        val folder = File(desktopOutputDirectory.get().asFile, packageName.get().replace('.', '/'))
        folder.deleteRecursively()
        folder.mkdirs()
        File(folder, "strings.properties").writeText(properties(english))
        for ((suffix, table) in translations) {
            val unknown = (table.strings.keys - english.strings.keys) + (table.plurals.keys - english.plurals.keys)
            if (unknown.isNotEmpty()) throw GradleException("values-$suffix has strings missing in English: $unknown")
            File(folder, "strings_$suffix.properties").writeText(properties(table))
        }
        File(folder, "locales.txt").writeText(translations.joinToString("") { "${it.first}\n" })
    }

    /** A `.properties` table, ASCII-only so its encoding never matters. */
    private fun properties(table: Table): String {
        val lines = mutableListOf("# Generated from l10n/res by the generateStrings task. Do not edit.")
        for ((name, text) in table.strings) lines += "$name=${escapeProperty(text)}"
        for ((name, items) in table.plurals) {
            for ((quantity, text) in items) lines += "$name.$quantity=${escapeProperty(text)}"
        }
        return lines.joinToString("\n", postfix = "\n")
    }

    private fun write(
        directory: DirectoryProperty,
        fileName: String,
        code: StringBuilder,
    ) {
        val folder = File(directory.get().asFile, packageName.get().replace('.', '/'))
        folder.mkdirs()
        File(folder, fileName).writeText(code.toString())
    }

    /**
     * The text of a resource as Android reads it: inner tags such as `<xliff:g>` dropped,
     * whitespace collapsed outside double quotes, unescaped double quotes removed, and the
     * escapes `\n`, `\t`, `\uXXXX`, `\'`, `\"`, `\\`, `\@` and `\?` resolved.
     */
    private fun androidText(
        element: Element,
        file: File,
    ): String {
        val raw = element.textContent
        val out = StringBuilder()
        var quoted = false
        var pendingSpace = false
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            when {
                char == '\\' && index + 1 < raw.length -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    val next = raw[index + 1]
                    index++
                    when (next) {
                        'n' -> {
                            out.append('\n')
                        }

                        't' -> {
                            out.append('\t')
                        }

                        'u' -> {
                            val hex = raw.substring(index + 1, minOf(index + 5, raw.length))
                            val code =
                                hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                                    ?: throw GradleException(
                                        "$file: invalid \\u escape in ${element.getAttribute("name")}",
                                    )
                            out.append(code.toChar())
                            index += 4
                        }

                        else -> {
                            out.append(next)
                        }
                    }
                }

                char == '"' -> {
                    quoted = !quoted
                }

                char.isWhitespace() && !quoted -> {
                    pendingSpace = true
                }

                else -> {
                    if (pendingSpace && out.isNotEmpty()) out.append(' ')
                    pendingSpace = false
                    out.append(char)
                }
            }
            index++
        }
        return out.toString()
    }

    /** `pt-rBR` to `pt_BR`, as Java names its resource bundles. */
    private fun bundleSuffix(folder: String): String {
        val qualifier = folder.removePrefix("values-")
        val match =
            LOCALE_QUALIFIER.matchEntire(qualifier)
                ?: throw GradleException(
                    "l10n/res/$folder: only language and region qualifiers (values-pt, values-pt-rBR) are supported.",
                )
        val (language, region) = match.destructured
        return if (region.isEmpty()) language else "${language}_$region"
    }

    private fun escapeProperty(text: String): String {
        val out = StringBuilder()
        text.forEachIndexed { index, char ->
            when {
                char == '\\' -> out.append("\\\\")
                char == '\n' -> out.append("\\n")
                char == '\t' -> out.append("\\t")
                char == '\r' -> out.append("\\r")
                char == ' ' && index == 0 -> out.append("\\ ")
                char.code < 0x20 || char.code > 0x7e -> out.append("\\u%04x".format(char.code))
                else -> out.append(char)
            }
        }
        return out.toString()
    }

    private fun kdoc(text: String): String =
        text
            .replace("*/", "* /")
            .replace("/*", "/ *")
            .replace('\n', ' ')

    private fun Element.childElements(): List<Element> {
        val nodes = (0 until childNodes.length).map { childNodes.item(it) }
        return nodes.filter { it.nodeType == Node.ELEMENT_NODE }.map { it as Element }
    }

    private companion object {
        val NAME = Regex("[a-z][a-z0-9_]*")
        val LOCALE_QUALIFIER = Regex("([a-z]{2,3})(?:-r([A-Z]{2}))?")
    }
}
