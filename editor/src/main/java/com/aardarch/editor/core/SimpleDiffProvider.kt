package com.aardarch.editor.core

object SimpleDiffProvider : DiffProvider {
    override fun diff(baseLines: List<String>, currentLines: List<String>): List<LineDiff> {
        val result = mutableListOf<LineDiff>()
        for (i in currentLines.indices) {
            when {
                i >= baseLines.size -> result.add(LineDiff(i, LineDiffKind.Added))
                currentLines[i] != baseLines[i] -> result.add(LineDiff(i, LineDiffKind.Modified))
            }
        }
        return result
    }
}
