// SPDX-License-Identifier: GPL-3.0-or-later
package stremionow.buildlogic

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Turns `design/tokens.json` into Compose code and Android color resources.
 *
 * Outputs:
 * - `SnTokens.kt` with `SnColors`, `SnSpacing`, `SnRadius`, `SnSize` and `SnTypography`.
 * - `values/sn_colors.xml` with every color as `@color/sn_<name>`, for window themes and drawables.
 *
 * Typography refers to `SnFontFamilies`, which the consuming module defines next to its font files.
 */
@CacheableTask
abstract class GenerateDesignTokensTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val tokensFile: RegularFileProperty

    /** Kotlin package of the generated code. */
    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val kotlinOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val resOutputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val tokens = JsonSlurper().parse(tokensFile.get().asFile).asMap("tokens.json")
        val colors = colorTokens(tokens)

        writeKotlin(tokens, colors)
        writeColorResources(colors)
    }

    private fun writeKotlin(tokens: Map<String, Any?>, colors: List<ColorToken>) {
        val code = StringBuilder()
        code.appendLine("// Generated from design/tokens.json by the generateDesignTokens task. Do not edit.")
        code.appendLine("package ${packageName.get()}")
        code.appendLine()
        code.appendLine("import androidx.compose.ui.graphics.Color")
        code.appendLine("import androidx.compose.ui.text.TextStyle")
        code.appendLine("import androidx.compose.ui.text.font.FontWeight")
        code.appendLine("import androidx.compose.ui.unit.dp")
        code.appendLine("import androidx.compose.ui.unit.em")
        code.appendLine("import androidx.compose.ui.unit.sp")
        code.appendLine()

        code.appendLine("/** Colors of the StremioNow design system. */")
        code.appendLine("object SnColors {")
        for (color in colors) {
            code.appendDoc(color.usage)
            val value = when (color) {
                is ColorToken.Literal -> "Color(0x${color.argb})"
                is ColorToken.Alias -> camelCase(color.target)
            }
            code.appendLine("    val ${camelCase(color.name)}: Color = $value")
        }
        code.appendLine("}")

        appendDimensionObject(code, "SnSpacing", "Spacing steps.", tokens, "spacing")
        appendDimensionObject(code, "SnRadius", "Corner radii.", tokens, "radius")
        appendDimensionObject(code, "SnSize", "Control heights and touch targets.", tokens, "size")
        appendTypography(code, tokens)

        val packageDirectory = File(kotlinOutputDirectory.get().asFile, packageName.get().replace('.', '/'))
        packageDirectory.mkdirs()
        File(packageDirectory, "SnTokens.kt").writeText(code.toString())
    }

    private fun appendDimensionObject(
        code: StringBuilder,
        objectName: String,
        summary: String,
        tokens: Map<String, Any?>,
        family: String,
    ) {
        val entries = tokens[family]?.asMap(family)?.get("tokens")?.asList("$family.tokens") ?: return
        code.appendLine()
        code.appendLine("/** $summary */")
        code.appendLine("object $objectName {")
        for (entry in entries) {
            val token = entry.asMap("$family token")
            val name = token.string("name")
            code.appendDoc(token["usage"] as? String)
            code.appendLine("    val ${camelCase(name)} = ${pixels(token.string("value"))}.dp")
        }
        code.appendLine("}")
    }

    private fun appendTypography(code: StringBuilder, tokens: Map<String, Any?>) {
        val groups = tokens["type"]?.asMap("type")?.get("groups")?.asList("type.groups") ?: return
        code.appendLine()
        code.appendLine("/** Text styles. Font families come from SnFontFamilies. */")
        code.appendLine("object SnTypography {")
        for (group in groups.map { it.asMap("type group") }) {
            val groupFamily = group["family"] as? String
            for (style in group["styles"].asList("type styles").map { it.asMap("type style") }) {
                val family = style["family"] as? String ?: groupFamily
                    ?: throw GradleException("Text style ${style["name"]} has no font family.")
                code.appendDoc(style["usage"] as? String)
                code.appendLine("    val ${camelCase(style.string("name"))}: TextStyle = TextStyle(")
                code.appendLine("        fontFamily = SnFontFamilies.${camelCase(family)},")
                style["fontWeight"]?.let { code.appendLine("        fontWeight = FontWeight(${number(it).roundToInt()}),") }
                code.appendLine("        fontSize = ${pixels(style.string("fontSize"))}.sp,")
                style["lineHeight"]?.let { code.appendLine("        lineHeight = ${lineHeight(it)},") }
                (style["letterSpacing"] as? String)?.let { code.appendLine("        letterSpacing = ${letterSpacing(it)},") }
                code.appendLine("    )")
            }
        }
        code.appendLine("}")
    }

    private fun writeColorResources(colors: List<ColorToken>) {
        val xml = StringBuilder()
        xml.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        xml.appendLine("<!-- Generated from design/tokens.json by the generateDesignTokens task. Do not edit. -->")
        xml.appendLine("<resources>")
        for (color in colors) {
            val value = when (color) {
                is ColorToken.Literal -> "#${color.argb}"
                is ColorToken.Alias -> "@color/${resourceName(color.target)}"
            }
            xml.appendLine("    <color name=\"${resourceName(color.name)}\">$value</color>")
        }
        xml.appendLine("</resources>")

        val values = File(resOutputDirectory.get().asFile, "values")
        values.mkdirs()
        File(values, "sn_colors.xml").writeText(xml.toString())
    }

    private sealed interface ColorToken {
        val name: String
        val usage: String?

        data class Literal(override val name: String, override val usage: String?, val argb: String) : ColorToken

        data class Alias(override val name: String, override val usage: String?, val target: String) : ColorToken
    }

    private fun colorTokens(tokens: Map<String, Any?>): List<ColorToken> {
        val color = tokens["color"].asMap("color")
        val firstTheme = color["themes"]?.asList("color.themes")?.firstOrNull()?.asMap("theme")?.get("id") as? String
        return color["tokens"].asList("color.tokens").map { entry ->
            val token = entry.asMap("color token")
            val name = token.string("name")
            val raw = when (val value = token["value"]) {
                is String -> value
                is Map<*, *> -> (value[firstTheme] ?: value.values.first()) as String
                else -> throw GradleException("Color $name has no value.")
            }
            val usage = token["usage"] as? String
            val alias = Regex("""^\{([A-Za-z0-9_.-]+)}$""").find(raw.trim())
            if (alias != null) {
                ColorToken.Alias(name, usage, alias.groupValues[1])
            } else {
                ColorToken.Literal(name, usage, argb(name, raw.trim()))
            }
        }
    }

    private fun argb(name: String, value: String): String {
        if (value.startsWith("#")) {
            val hex = value.removePrefix("#").uppercase(Locale.ROOT)
            return when (hex.length) {
                3 -> "FF" + hex.map { "$it$it" }.joinToString("")
                6 -> "FF$hex"
                8 -> hex.substring(6) + hex.substring(0, 6)
                else -> throw GradleException("Color $name has an invalid hex value: $value")
            }
        }
        val rgba = Regex("""^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$""").find(value)
            ?: throw GradleException("Color $name uses an unsupported format: $value")
        val (r, g, b) = rgba.destructured
        val alpha = rgba.groupValues[4].ifEmpty { "1" }.toDouble()
        return listOf((alpha * 255).roundToInt(), r.toDouble().roundToInt(), g.toDouble().roundToInt(), b.toDouble().roundToInt())
            .joinToString("") { "%02X".format(it.coerceIn(0, 255)) }
    }

    private fun pixels(value: String): String = value.trim().removeSuffix("px").toDouble().let(::kotlinNumber)

    private fun lineHeight(value: Any): String =
        when (value) {
            is Number -> "${kotlinNumber(value.toDouble())}.em"
            is String -> if (value.endsWith("px")) "${pixels(value)}.sp" else "${kotlinNumber(value.toDouble())}.em"
            else -> throw GradleException("Unsupported line height: $value")
        }

    private fun letterSpacing(value: String): String {
        val trimmed = value.trim()
        return when {
            trimmed.endsWith("em") -> "(${kotlinNumber(trimmed.removeSuffix("em").toDouble())}).em"
            trimmed.endsWith("px") -> "(${pixels(trimmed)}).sp"
            else -> "(${kotlinNumber(trimmed.toDouble())}).sp"
        }
    }

    private fun number(value: Any): Double = (value as? Number)?.toDouble() ?: value.toString().toDouble()

    private fun kotlinNumber(value: Double): String =
        if (value == value.roundToInt().toDouble()) value.roundToInt().toString() else value.toString()

    private fun camelCase(name: String): String {
        val parts = name.split('-', '.', '_').filter { it.isNotEmpty() }
        return parts.first().lowercase(Locale.ROOT) +
            parts.drop(1).joinToString("") { part -> part.replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    private fun resourceName(name: String): String = "sn_" + name.replace('-', '_').replace('.', '_').lowercase(Locale.ROOT)

    private fun StringBuilder.appendDoc(text: String?) {
        if (text.isNullOrBlank()) return
        appendLine("    /** ${text.replace("*/", "* /")} */")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(what: String): Map<String, Any?> =
        this as? Map<String, Any?> ?: throw GradleException("Expected an object for $what in tokens.json.")

    private fun Any?.asList(what: String): List<Any?> =
        this as? List<Any?> ?: throw GradleException("Expected a list for $what in tokens.json.")

    private fun Map<String, Any?>.string(key: String): String =
        this[key] as? String ?: throw GradleException("Missing \"$key\" in tokens.json.")
}
