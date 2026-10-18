package com.vknxworks.proxctrl.data.guests

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.pow
import kotlin.math.roundToInt

/** Background and text colour of a tag chip, as 0xRRGGBB. */
data class TagColor(val background: Int, val text: Int)

/**
 * Tag colours exactly as the Proxmox web UI draws them: the datacenter's tag-style color-map
 * ("tag:RRGGBB[:RRGGBB];…") where set, otherwise a colour derived from the tag's name
 * (Proxmox.Utils.stringToRGB) with black or white text picked for contrast.
 */
class TagColors(private val overrides: Map<String, TagColor> = emptyMap()) {

    fun colorOf(tag: String): TagColor = overrides[tag] ?: derived(tag)

    companion object {
        val DEFAULT = TagColors()

        /** Reads tag-style from GET /cluster/options (an object, or the raw property string on some versions). */
        fun fromClusterOptions(options: JsonElement?): TagColors {
            val style = (options as? JsonObject)?.get("tag-style") ?: return DEFAULT
            val colorMap = when (style) {
                is JsonObject -> (style["color-map"] as? JsonPrimitive)?.content
                is JsonPrimitive -> style.content.split(',').firstOrNull { it.trim().startsWith("color-map=") }?.substringAfter('=')
                else -> null
            } ?: return DEFAULT
            return TagColors(parseColorMap(colorMap))
        }

        fun parseColorMap(map: String): Map<String, TagColor> = map.split(';').mapNotNull { entry ->
            val parts = entry.trim().split(':')
            if (parts.size < 2) return@mapNotNull null
            val bg = parts[1].trim().removePrefix("#").toIntOrNull(16) ?: return@mapNotNull null
            val fg = parts.getOrNull(2)?.trim()?.removePrefix("#")?.toIntOrNull(16) ?: contrastText(bg)
            parts[0].trim() to TagColor(bg, fg)
        }.toMap()

        /** Port of Proxmox.Utils.stringToRGB: a 32-bit string hash blended 70 % over white. */
        fun derived(tag: String): TagColor {
            var hash = 0
            val s = tag + "prox"
            for (ch in s) {
                hash = ch.code + ((hash shl 5) - hash)
            }
            fun channel(shift: Int): Int = (((hash shr shift) and 255) * 0.7 + 255 * 0.3).roundToInt().coerceIn(0, 255)
            val rgb = (channel(0) shl 16) or (channel(8) shl 8) or channel(16)
            return TagColor(rgb, contrastText(rgb))
        }

        /** Port of Proxmox.Utils.getTextContrastClass (simplified SAPC): white or black text. */
        fun contrastText(rgb: Int): Int {
            fun lin(c: Int) = (c / 255.0).pow(2.4)
            var bg = lin((rgb shr 16) and 255) * 0.2126729 + lin((rgb shr 8) and 255) * 0.7151522 + lin(rgb and 255) * 0.072175
            if (bg <= 0.022) bg += (0.022 - bg).pow(1.414)
            val contrastLight = bg.pow(0.65) - 1
            val contrastDark = bg.pow(0.56) - 0.046134502
            return if (kotlin.math.abs(contrastLight) >= kotlin.math.abs(contrastDark)) 0xFFFFFF else 0x000000
        }
    }
}
