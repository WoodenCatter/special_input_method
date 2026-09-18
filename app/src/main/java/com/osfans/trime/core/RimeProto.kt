/*
 * Minimal JNI value types derived from Trime v3.3.11.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.osfans.trime.core

data class CommitProto(val text: String?)

data class CandidateProto(val text: String, val comment: String, val label: String)

data class CompositionProto(
    val length: Int = 0,
    val cursorPos: Int = 0,
    val selStart: Int = 0,
    val selEnd: Int = 0,
    val preedit: String? = null,
    val commitTextPreview: String? = null
)

data class MenuProto(
    val pageSize: Int = 0,
    val pageNumber: Int = 0,
    val isLastPage: Boolean = false,
    val highlightedCandidateIndex: Int = 0,
    val candidates: Array<CandidateProto> = emptyArray(),
    val selectKeys: String? = null,
    val selectLabels: Array<String> = emptyArray()
)

data class ContextProto(
    val composition: CompositionProto = CompositionProto(),
    val menu: MenuProto = MenuProto(),
    val input: String = "",
    val caretPos: Int = 0
)

data class StatusProto(
    val schemaId: String = "",
    val schemaName: String = "",
    val isDisabled: Boolean = true,
    val isComposing: Boolean = false,
    val isAsciiMode: Boolean = true,
    val isFullShape: Boolean = false,
    val isSimplified: Boolean = false,
    val isTraditional: Boolean = false,
    val isAsciiPunct: Boolean = true
)

data class SchemaItem(val schemaId: String, val name: String)

data class RimeKeyEvent(val keycode: Int, val mask: Int, val text: String)
