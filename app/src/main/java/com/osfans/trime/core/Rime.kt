/*
 * Minimal JNI surface compatible with Trime v3.3.11 librime_jni.so.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.osfans.trime.core

class Rime private constructor() {
    companion object {
        @Volatile
        var notificationListener: ((type: Int, parameters: Array<Any>) -> Unit)? = null

        init {
            System.loadLibrary("rime_jni")
        }

        @JvmStatic
        external fun startupRime(sharedDir: String, userDir: String, versionName: String, fullCheck: Boolean)

        @JvmStatic external fun exitRime()
        @JvmStatic external fun processRimeKey(keycode: Int, mask: Int): Boolean
        @JvmStatic external fun clearRimeComposition()
        @JvmStatic external fun setRimeOption(option: String, value: Boolean)
        @JvmStatic external fun getRimeSchemaList(): Array<SchemaItem>
        @JvmStatic external fun selectRimeSchema(schemaId: String): Boolean
        @JvmStatic external fun selectRimeCandidate(index: Int, global: Boolean): Boolean
        @JvmStatic external fun getRimeCandidates(startIndex: Int, limit: Int): Array<CandidateProto>

        @JvmStatic
        fun handleRimeMessage(type: Int, parameters: Array<Any>) {
            runCatching { notificationListener?.invoke(type, parameters) }
        }
    }
}
