/*
 * Minimal JNI surface compatible with Trime v3.3.11 librime_jni.so.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.osfans.trime.data.opencc

object OpenCCDictManager {
    init {
        System.loadLibrary("rime_jni")
    }

    @JvmStatic
    external fun openCCDictConv(src: String, dest: String, mode: Boolean)

    @JvmStatic
    external fun openCCLineConv(input: String, configFileName: String): String

    const val MODE_TEXT_TO_BIN = false
}
