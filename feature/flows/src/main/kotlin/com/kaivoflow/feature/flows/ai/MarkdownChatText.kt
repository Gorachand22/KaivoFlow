package com.kaivoflow.feature.flows.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaivoflow.core.design.KaivoTheme

private sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class CodeBlock(val code: String, val lang: String) : MarkdownBlock
    data class BulletItem(val text: String) : MarkdownBlock
    data class Table(val rows: List<List<String>>) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

/**
 * Rich Markdown formatter for chat messages.
 * Formats bold (**text**), italics (*text*), inline code (`code`),
 * fenced code blocks, bullet points, headers, and tables.
 */
@Composable
fun MarkdownChatText(
    text: String,
    modifier: Modifier = Modifier,
    isUser: Boolean = false,
) {
    val colors = KaivoTheme.colors
    val blocks = parseMarkdownBlocks(text)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Header -> {
                    val fontSize = when (block.level) {
                        1 -> 18.sp
                        2 -> 16.sp
                        else -> 15.sp
                    }
                    Text(
                        text = buildInlineMarkdown(block.text, colors.inkPrimary, colors.royalGold),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = fontSize,
                            color = colors.inkPrimary,
                        ),
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF101014), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF242430), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            color = Color(0xFFE2E8F0),
                            lineHeight = 18.sp,
                        )
                    }
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "•",
                            color = colors.royalGold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Text(
                            text = buildInlineMarkdown(block.text, colors.inkPrimary, colors.royalGold),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.inkPrimary,
                            lineHeight = 20.sp,
                        )
                    }
                }
                is MarkdownBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF14141A), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(0xFF242430), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        block.rows.forEachIndexed { idx, row ->
                            val isHeader = idx == 0
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                row.forEach { cell ->
                                    Text(
                                        text = cell.trim(),
                                        fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 12.sp,
                                        color = if (isHeader) colors.royalGold else colors.inkPrimary,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            if (isHeader) {
                                HorizontalDivider(
                                    color = Color(0xFF282836),
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = buildInlineMarkdown(block.text, colors.inkPrimary, colors.royalGold),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.inkPrimary,
                        lineHeight = 20.sp,
                    )
                }
            }
        }
    }
}

private fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val lines = text.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(code = codeLines.joinToString("\n"), lang = lang))
            i++
            continue
        }

        // Header
        val trimmed = line.trim()
        if (trimmed.startsWith("### ")) {
            blocks.add(MarkdownBlock.Header(3, trimmed.removePrefix("### ").trim()))
            i++
            continue
        } else if (trimmed.startsWith("## ")) {
            blocks.add(MarkdownBlock.Header(2, trimmed.removePrefix("## ").trim()))
            i++
            continue
        } else if (trimmed.startsWith("# ")) {
            blocks.add(MarkdownBlock.Header(1, trimmed.removePrefix("# ").trim()))
            i++
            continue
        }

        // Bullet point
        if (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("• ")) {
            val content = trimmed.substring(2).trim()
            blocks.add(MarkdownBlock.BulletItem(content))
            i++
            continue
        }

        // Table row detection
        if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
            val tableRows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|")) {
                val rowLine = lines[i].trim()
                // Skip markdown table separator rows like |---|---|
                if (!rowLine.matches(Regex("\\|[\\s\\-:]+\\|.*"))) {
                    val cells = rowLine.split("|")
                        .filterIndexed { index, _ -> index > 0 && index < rowLine.split("|").lastIndex }
                    tableRows.add(cells)
                }
                i++
            }
            if (tableRows.isNotEmpty()) {
                blocks.add(MarkdownBlock.Table(tableRows))
            }
            continue
        }

        // Normal paragraph (combine non-empty lines)
        if (trimmed.isNotBlank()) {
            val paraLines = mutableListOf<String>()
            while (i < lines.size &&
                lines[i].trim().isNotBlank() &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].trim().startsWith("#") &&
                !lines[i].trim().startsWith("* ") &&
                !lines[i].trim().startsWith("- ") &&
                !(lines[i].trim().startsWith("|") && lines[i].trim().endsWith("|"))
            ) {
                paraLines.add(lines[i])
                i++
            }
            blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString(" ")))
            continue
        }

        i++
    }

    return blocks
}

/**
 * Builds an AnnotatedString parsing inline formatting:
 * **bold**, *italic*, and `inline code`.
 */
private fun buildInlineMarkdown(text: String, primaryColor: Color, highlightColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var cursor = 0
        // Regex matches **bold**, `code`, or *italic*
        val pattern = Regex("(\\*\\*([^*]+)\\*\\*)|(`([^`]+)`)|(\\*([^*]+)\\*)")
        val matches = pattern.findAll(text)

        for (match in matches) {
            val start = match.range.first
            val end = match.range.last + 1

            if (start > cursor) {
                append(text.substring(cursor, start))
            }

            when {
                // **bold**
                match.value.startsWith("**") && match.value.endsWith("**") -> {
                    val inner = match.groupValues[2]
                    val spanStart = length
                    append(inner)
                    addStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                        ),
                        spanStart,
                        length,
                    )
                }
                // `code`
                match.value.startsWith("`") && match.value.endsWith("`") -> {
                    val inner = match.groupValues[4]
                    val spanStart = length
                    append(inner)
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = highlightColor,
                            background = Color(0x28FFD700),
                        ),
                        spanStart,
                        length,
                    )
                }
                // *italic*
                match.value.startsWith("*") && match.value.endsWith("*") -> {
                    val inner = match.groupValues[6]
                    val spanStart = length
                    append(inner)
                    addStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            color = primaryColor,
                        ),
                        spanStart,
                        length,
                    )
                }
            }

            cursor = end
        }

        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
