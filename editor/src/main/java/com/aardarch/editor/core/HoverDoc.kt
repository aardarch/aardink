package com.aardarch.editor.core

/**
 * Documentation shown in a hover popup when the cursor rests on a token.
 *
 * @param title Primary heading (e.g. element name, property name, transform name).
 * @param content Markdown-formatted body text.
 * @param example Optional code example shown below the content.
 * @param range Document range to highlight while the popup is visible. If null the cursor token's
 *   range is used.
 */
data class HoverDoc(val title: String, val content: String, val example: String? = null, val range: IntRange? = null)
