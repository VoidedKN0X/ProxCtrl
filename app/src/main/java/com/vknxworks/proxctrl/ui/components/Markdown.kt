package com.vknxworks.proxctrl.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.LinkAnnotation
import com.vknxworks.proxctrl.data.lock.AppLock
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A small Markdown renderer for guest notes, covering what people put there: headings, paragraphs,
 * bullet and numbered lists, code blocks, and inline bold, italic, code and links. Anything else
 * shows as plain text. Links open only http(s) and mailto addresses.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val uriHandler = LocalUriHandler.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Heading -> Text(
                    inline(block.text, linkColor, codeBg, uriHandler),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is Block.Paragraph -> Text(inline(block.text, linkColor, codeBg, uriHandler), style = MaterialTheme.typography.bodyMedium)
                is Block.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEachIndexed { i, item ->
                        Row {
                            Text(if (block.ordered) "${i + 1}." else "•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(22.dp))
                            Text(inline(item, linkColor, codeBg, uriHandler), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                is Block.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(codeBg).padding(10.dp),
                )
                Block.Rule -> Text("—", color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

private sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class ListBlock(val ordered: Boolean, val items: List<String>) : Block
    data class Code(val text: String) : Block
    data object Rule : Block
}

private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
private val NUMBERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")
private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*$")

private fun parseBlocks(md: String): List<Block> {
    val out = mutableListOf<Block>()
    val lines = md.replace("\r\n", "\n").split('\n')
    val para = StringBuilder()
    var list: Pair<Boolean, MutableList<String>>? = null
    fun flushPara() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    fun flushList() {
        list?.let { (ordered, items) -> out += Block.ListBlock(ordered, items.toList()) }
        list = null
    }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.trimStart().startsWith("```") -> {
                flushPara(); flushList()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                out += Block.Code(code.toString().trimEnd())
            }
            HEADING.matches(line) -> {
                flushPara(); flushList()
                val m = HEADING.find(line)!!
                out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            line.trim().let { it == "---" || it == "***" || it == "___" } -> {
                flushPara(); flushList(); out += Block.Rule
            }
            BULLET.matches(line) || NUMBERED.matches(line) -> {
                flushPara()
                val ordered = NUMBERED.matches(line)
                val text = (if (ordered) NUMBERED else BULLET).find(line)!!.groupValues[1]
                val current = list
                if (current == null || current.first != ordered) {
                    flushList()
                    list = ordered to mutableListOf(text)
                } else {
                    current.second += text
                }
            }
            line.isBlank() -> {
                flushPara(); flushList()
            }
            else -> {
                flushList()
                if (para.isNotEmpty()) para.append(if (line.endsWith("  ")) "\n" else " ")
                para.append(line.trim())
            }
        }
        i++
    }
    flushPara(); flushList()
    return out
}

private val INLINE = Regex("""(\*\*|__)(.+?)\1|(\*|_)(.+?)\3|`([^`]+)`|\[([^\]]+)]\(([^)\s]+)\)""")

private fun inline(text: String, linkColor: Color, codeBg: Color, uriHandler: UriHandler): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in INLINE.findAll(text)) {
        append(text.substring(last, m.range.first))
        val g = m.groupValues
        when {
            g[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(g[2]) }
            g[4].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(g[4]) }
            g[5].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(g[5]) }
            g[6].isNotEmpty() -> {
                val url = g[7]
                if (url.startsWith("https://", true) || url.startsWith("http://", true) || url.startsWith("mailto:", true)) {
                    withLink(
                        LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))) {
                            // Opening a link isn't leaving the app for the app lock.
                            AppLock.expectReturn()
                            runCatching { uriHandler.openUri(url) }
                        },
                    ) { append(g[6]) }
                } else {
                    append(g[6])
                }
            }
            else -> append(m.value)
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
