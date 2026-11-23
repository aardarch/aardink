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

import com.aardarch.aardink.core.SymbolKind

/** Brazilian Portuguese (`pt-BR`) [EditorStrings] implementation. */
object EditorStringsPtBr : EditorStrings {
    override val findPlaceholder: String = "Buscar"
    override val replacePlaceholder: String = "Substituir"
    override val replaceButton: String = "Substituir"
    override val replaceAllButton: String = "Substituir tudo"
    override val findShowReplaceDescription: String = "Mostrar substituição"
    override val findHideReplaceDescription: String = "Ocultar substituição"
    override val findPreviousMatchDescription: String = "Ocorrência anterior"
    override val findNextMatchDescription: String = "Próxima ocorrência"
    override val findCloseDescription: String = "Fechar busca e substituição"
    override val findMatchCaseDescription: String = "Diferenciar maiúsculas e minúsculas"
    override val findWholeWordDescription: String = "Palavra inteira"
    override val findRegexDescription: String = "Usar expressão regular"
    override val findInSelection: String = "Na seleção"
    override val findPreserveCase: String = "Preservar maiúsculas/minúsculas"
    override val findSearchAllFiles: String = "Buscar em todos os arquivos"

    override val goToLineTitle: String = "Ir para a linha"
    override val goToLineLabel: String = "Número da linha"
    override val goToLineConfirm: String = "Ir"
    override val renameTitle: String = "Renomear símbolo"
    override fun renamePrompt(currentName: String): String = "Digite o novo nome para '$currentName':"
    override val renameNewNameLabel: String = "Novo nome"
    override val renameConfirm: String = "Renomear"
    override val cancel: String = "Cancelar"
    override val apply: String = "Aplicar"

    override val cut: String = "Recortar"
    override val copy: String = "Copiar"
    override val paste: String = "Colar"
    override val selectAll: String = "Selecionar tudo"
    override val selectAllMenu: String = "Selecionar tudo"
    override val info: String = "Informações"
    override val format: String = "Formatar"
    override val formatDocument: String = "Formatar documento"
    override val formatSelection: String = "Formatar seleção"
    override val goToDefinition: String = "Ir para definição"
    override val findReferences: String = "Encontrar referências"
    override val color: String = "Cor"
    override val pickColor: String = "Escolher cor\u2026"

    override val quickFix: String = "Correção rápida"
    override val quickFixEllipsis: String = "Correção rápida\u2026"
    override val quickFixChip: String = "\uD83D\uDCA1 Correção rápida"
    override val quickFixesAndActions: String = "Correções rápidas e ações"
    override val dismissDiagnostic: String = "Dispensar diagnóstico"
    override fun diagnosticCounter(message: String, current: Int, total: Int): String = "$message  ($current de $total)"

    override val toolbarUndo: String = "Desfazer"
    override val toolbarRedo: String = "Refazer"
    override val toolbarMoveLeft: String = "Mover cursor para a esquerda"
    override val toolbarMoveRight: String = "Mover cursor para a direita"
    override fun toolbarInsertChar(char: String): String = "Inserir $char"

    override val goToSymbolPlaceholder: String = "Ir para símbolo"
    override val noSymbols: String = "Nenhum símbolo"
    override val noMatchingSymbols: String = "Nenhum símbolo correspondente"
    override fun symbolKindLabel(kind: SymbolKind): String = when (kind) {
        SymbolKind.Class -> "classe"
        SymbolKind.Method -> "método"
        SymbolKind.Function -> "função"
        SymbolKind.Constructor -> "construtor"
        SymbolKind.Property -> "propriedade"
        SymbolKind.Field -> "campo"
        SymbolKind.Variable -> "variável"
        SymbolKind.Constant -> "constante"
        SymbolKind.Interface -> "interface"
        SymbolKind.Enum -> "enum"
        SymbolKind.EnumMember -> "membro"
        SymbolKind.Object -> "objeto"
        SymbolKind.Array -> "array"
        SymbolKind.Key -> "chave"
        SymbolKind.Module -> "módulo"
        SymbolKind.Namespace -> "namespace"
        SymbolKind.Package -> "pacote"
        else -> kind.name.lowercase()
    }
    override fun referencesCount(count: Int): String = if (count == 1) "1 referência" else "$count referências"

    override val diffAcceptChange: String = "Aceitar alteração"
    override val diffRevertChange: String = "Reverter alteração"
    override val diffNextChange: String = "Próxima alteração"
    override val diffPreviousChange: String = "Alteração anterior"
    override fun diffUnchangedLines(count: Int): String = if (count == 1) "1 linha sem alteração" else "$count linhas sem alteração"
}
