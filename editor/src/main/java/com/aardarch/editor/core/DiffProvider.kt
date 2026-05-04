package com.aardarch.editor.core

fun interface DiffProvider {
    fun diff(baseLines: List<String>, currentLines: List<String>): List<LineDiff>
}

data class LineDiff(val lineIndex: Int, val kind: LineDiffKind)

enum class LineDiffKind { Added, Modified }
