/*
 * Copyright 2026 Aardarch
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aardarch.aardink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** How tall documentation gets before it scrolls: a card has to stay inside the window. */
private val DOC_MAX_HEIGHT = 240.dp

/** How [markdownAnnotated] marks inline code and links. */
internal data class MarkdownSpans(val code: SpanStyle, val link: SpanStyle)

/**
 * [text] as Markdown, for hover and signature help: see [parseMarkdown] for what it understands.
 * Parsed once per string; documentation is short, and is cut at [MARKDOWN_MAX_LENGTH] when it is
 * not. A link goes to [onOpenLink] and nowhere else; with none, it shows as its text.
 */
@Composable
internal fun MarkdownDoc(text: String, style: TextStyle, color: Color, onOpenLink: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseMarkdown(text) }
    val currentOnOpenLink by rememberUpdatedState(onOpenLink)
    val linked = onOpenLink != null
    val open: ((String) -> Unit)? = remember(linked) { if (linked) { target -> currentOnOpenLink?.invoke(target) } else null }
    val spans = MarkdownSpans(
        code = SpanStyle(
            fontFamily = LocalEditorTypography.current.fontFamily,
            background = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        link = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
    )
    Column(
        modifier = modifier.heightIn(max = DOC_MAX_HEIGHT).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MarkdownBlocks(blocks, style, color, spans, open)
    }
}

@Composable
private fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    style: TextStyle,
    color: Color,
    spans: MarkdownSpans,
    onOpenLink: ((String) -> Unit)?,
) {
    for (block in blocks) {
        when (block) {
            is MarkdownBlock.Heading -> MarkdownText(block.text, headingStyle(block.level), color, spans, onOpenLink)

            is MarkdownBlock.Paragraph -> MarkdownText(block.text, style, color, spans, onOpenLink)

            is MarkdownBlock.Code -> DocCodePanel(block.code)

            is MarkdownBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row {
                        Text(
                            text = if (block.ordered) "${block.start + index}." else "•",
                            modifier = Modifier.widthIn(min = 16.dp).padding(end = 4.dp),
                            style = style,
                            color = color,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MarkdownBlocks(item, style, color, spans, onOpenLink)
                        }
                    }
                }
            }

            MarkdownBlock.Rule -> HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

@Composable
private fun MarkdownText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    color: Color,
    spans: MarkdownSpans,
    onOpenLink: ((String) -> Unit)?,
) {
    Text(text = remember(inlines, spans, onOpenLink) { markdownAnnotated(inlines, spans, onOpenLink) }, style = style, color = color)
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.titleMedium
    2 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.labelLarge
}.copy(fontWeight = FontWeight.SemiBold)

/**
 * [inlines] as styled text. A link is a [LinkAnnotation.Clickable], never a [LinkAnnotation.Url]:
 * a Url would open itself through the platform's `UriHandler`, past the host. With no [onOpenLink]
 * it is just its text, so nothing looks clickable that is not.
 */
internal fun markdownAnnotated(inlines: List<MarkdownInline>, spans: MarkdownSpans, onOpenLink: ((String) -> Unit)?): AnnotatedString =
    buildAnnotatedString { appendInlines(inlines, spans, onOpenLink) }

private fun AnnotatedString.Builder.appendInlines(inlines: List<MarkdownInline>, spans: MarkdownSpans, onOpenLink: ((String) -> Unit)?) {
    for (node in inlines) {
        when (node) {
            is MarkdownInline.Plain -> append(node.text)

            is MarkdownInline.Code -> withStyle(spans.code) { append(node.text) }

            is MarkdownInline.Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                appendInlines(node.children, spans, onOpenLink)
            }

            is MarkdownInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInlines(node.children, spans, onOpenLink)
            }

            is MarkdownInline.Link -> if (onOpenLink == null) {
                appendInlines(node.children, spans, null)
            } else {
                val link = LinkAnnotation.Clickable(node.target, TextLinkStyles(spans.link)) { onOpenLink(node.target) }
                withLink(link) { appendInlines(node.children, spans, onOpenLink) }
            }

            MarkdownInline.Break -> append('\n')
        }
    }
}

/** Code as hover cards show it, a code block or a [com.aardarch.aardink.core.HoverDoc.example]: code-font text on an inset panel. */
@Composable
internal fun DocCodePanel(code: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = code,
            modifier = Modifier.padding(8.dp),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = LocalEditorTypography.current.fontFamily,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
