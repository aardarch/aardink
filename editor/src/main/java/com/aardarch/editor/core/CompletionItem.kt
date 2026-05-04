package com.aardarch.editor.core

/**
 * A single item in a completion list.
 *
 * @param label Display text shown in the dropdown.
 * @param kind Visual classification controlling the icon shown beside the label.
 * @param insertText Text inserted into the document when the item is accepted.
 *   May differ from [label] — e.g. a snippet that inserts `<text>|</text>` when the label is `text`.
 * @param documentation Optional secondary text shown below the label in the dropdown.
 * @param filterText String used for fuzzy-filtering as the user continues typing. Defaults to [label].
 * @param sortPriority Lower values sort earlier in the list (0 = highest priority).
 */
data class CompletionItem(
    val label: String,
    val kind: CompletionKind,
    val insertText: String,
    val documentation: String? = null,
    val filterText: String = label,
    val sortPriority: Int = 0,
)

enum class CompletionKind {
    /** XML element name (e.g. `text`, `layer`). */
    Element,

    /** XML attribute name (e.g. `color`, `align`). */
    Attribute,

    /** An attribute value, often an enum (e.g. `center`, `start`). */
    Value,

    /** A multi-token snippet (e.g. full `<module .../>` boilerplate). */
    Snippet,

    /** A data module type or declared module ID (for expression module context). */
    Module,

    /** A module property name (for expression property context). */
    Property,

    /** An expression transform name (e.g. `uppercase`, `round`). */
    Transform,

    /** A colour reference name (e.g. `primary` after `@`). */
    ColorRef,
}
