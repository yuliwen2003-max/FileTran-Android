package com.yuliwen.filetran

import android.util.Base64

object TextShareCodec {
    private const val PREFIX = "FT_TEXT:"

    fun encode(text: String): String {
        val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE)
        return PREFIX + encoded
    }

    fun tryDecode(payload: String): String? {
        if (!payload.startsWith(PREFIX)) return null
        val content = payload.removePrefix(PREFIX)
        return try {
            val bytes = Base64.decode(content, Base64.NO_WRAP or Base64.URL_SAFE)
            bytes.toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
