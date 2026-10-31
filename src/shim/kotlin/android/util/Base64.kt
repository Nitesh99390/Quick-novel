package android.util

/** Subset of android.util.Base64 implemented on top of java.util.Base64. */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray = decode(str.toByteArray(Charsets.US_ASCII), flags)

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        // Android's decoder ignores whitespace and accepts missing padding.
        var s = String(input, Charsets.US_ASCII).filterNot { it.isWhitespace() }
        s = if (flags and URL_SAFE != 0) s.replace('-', '+').replace('_', '/') else s
        val pad = (4 - s.length % 4) % 4
        if (pad in 1..2) s += "=".repeat(pad)
        return try {
            java.util.Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad base-64", e)
        }
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray = encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        var encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) encoder = encoder.withoutPadding()
        val out = encoder.encodeToString(input)
        if (flags and NO_WRAP != 0) return out
        // Android wraps at 76 chars and terminates with a newline by default
        val nl = if (flags and CRLF != 0) "\r\n" else "\n"
        return out.chunked(76).joinToString(nl, postfix = nl)
    }
}

/** Minimal android.util.Log that writes to stdout/stderr. */
object Log {
    @JvmStatic fun d(tag: String?, msg: String?): Int { println("D/$tag: $msg"); return 0 }
    @JvmStatic fun i(tag: String?, msg: String?): Int { println("I/$tag: $msg"); return 0 }
    @JvmStatic fun w(tag: String?, msg: String?): Int { System.err.println("W/$tag: $msg"); return 0 }
    @JvmStatic fun e(tag: String?, msg: String?): Int { System.err.println("E/$tag: $msg"); return 0 }
    @JvmStatic fun e(tag: String?, msg: String?, tr: Throwable?): Int {
        System.err.println("E/$tag: $msg"); tr?.printStackTrace(); return 0
    }
}
