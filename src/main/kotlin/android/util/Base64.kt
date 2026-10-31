package android.util

/** JVM implementation of android.util.Base64 backed by java.util.Base64 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    private fun decoder(flags: Int) =
        if (flags and URL_SAFE != 0) java.util.Base64.getUrlDecoder() else java.util.Base64.getMimeDecoder()

    private fun clean(input: String, flags: Int): String {
        // Android's decoder is lenient: whitespace & missing padding are accepted
        var s = input.filterNot { it.isWhitespace() }
        if (flags and URL_SAFE == 0) s = s.replace('-', '+').replace('_', '/')
        val pad = (4 - s.length % 4) % 4
        if (pad in 1..2 && !s.endsWith("=")) s += "=".repeat(pad)
        return s
    }

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray = decoder(flags).decode(clean(str, flags))

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input, Charsets.ISO_8859_1), flags)

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray = encodeToString(input, flags).toByteArray(Charsets.ISO_8859_1)

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        var enc = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        if (flags and NO_PADDING != 0) enc = enc.withoutPadding()
        val out = enc.encodeToString(input)
        // Android DEFAULT wraps lines at 76 chars and appends a newline
        if (flags and NO_WRAP != 0) return out
        val sep = if (flags and CRLF != 0) "\r\n" else "\n"
        return out.chunked(76).joinToString(sep) + sep
    }
}
