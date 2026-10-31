package android.net

import java.net.URLEncoder
import java.net.URLDecoder

/**
 * JVM implementation of the parts of android.net.Uri used by QuickNovel providers.
 * Behaviour mirrors Android (RFC 2396 style encoding, `appendPath` encodes the segment, …).
 */
class Uri private constructor(
    val scheme: String?,
    /** already encoded authority, e.g. `example.com:8080` */
    val encodedAuthority: String?,
    /** already encoded path, e.g. `/a/b%20c` */
    val encodedPath: String?,
    /** already encoded query (without `?`) */
    val encodedQuery: String?,
    /** already encoded fragment (without `#`) */
    val encodedFragment: String?,
    private val opaque: String? = null,
) {
    val host: String? get() = encodedAuthority?.substringAfterLast('@')?.substringBefore(':')
    val path: String? get() = encodedPath?.let { decode(it) }
    val query: String? get() = encodedQuery?.let { decode(it) }
    val fragment: String? get() = encodedFragment?.let { decode(it) }
    val authority: String? get() = encodedAuthority?.let { decode(it) }
    val pathSegments: List<String>
        get() = encodedPath.orEmpty().split('/').filter { it.isNotEmpty() }.map { decode(it) }
    val lastPathSegment: String? get() = pathSegments.lastOrNull()

    fun getQueryParameter(key: String): String? = queryPairs().firstOrNull { it.first == key }?.second
    fun getQueryParameters(key: String): List<String> = queryPairs().filter { it.first == key }.map { it.second }
    val queryParameterNames: Set<String> get() = queryPairs().map { it.first }.toCollection(LinkedHashSet())

    private fun queryPairs(): List<Pair<String, String>> = encodedQuery.orEmpty().split('&')
        .filter { it.isNotEmpty() }
        .map { kv -> decode(kv.substringBefore('=')) to decode(kv.substringAfter('=', "")) }

    fun buildUpon(): Builder = Builder().also {
        it.scheme = scheme
        it.authority = encodedAuthority
        it.path = encodedPath
        it.query = encodedQuery
        it.fragment = encodedFragment
    }

    override fun toString(): String {
        if (opaque != null) return opaque
        return buildString {
            if (scheme != null) append(scheme).append(':')
            if (encodedAuthority != null) append("//").append(encodedAuthority)
            if (!encodedPath.isNullOrEmpty()) {
                if (encodedAuthority != null && !encodedPath.startsWith('/')) append('/')
                append(encodedPath)
            }
            if (encodedQuery != null) append('?').append(encodedQuery)
            if (encodedFragment != null) append('#').append(encodedFragment)
        }
    }

    override fun equals(other: Any?): Boolean = other is Uri && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()

    class Builder {
        internal var scheme: String? = null
        internal var authority: String? = null
        internal var path: String? = null
        internal var query: String? = null
        internal var fragment: String? = null

        fun scheme(scheme: String?): Builder = apply { this.scheme = scheme }
        fun authority(authority: String?): Builder = apply { this.authority = authority?.let { encode(it, "@:") } }
        fun encodedAuthority(authority: String?): Builder = apply { this.authority = authority }
        fun path(path: String?): Builder = apply { this.path = path?.let { encode(it, "/") } }
        fun encodedPath(path: String?): Builder = apply { this.path = path }
        fun fragment(fragment: String?): Builder = apply { this.fragment = fragment?.let { encode(it) } }
        fun encodedFragment(fragment: String?): Builder = apply { this.fragment = fragment }
        fun query(query: String?): Builder = apply { this.query = query?.let { encode(it, "=&") } }
        fun encodedQuery(query: String?): Builder = apply { this.query = query }
        fun clearQuery(): Builder = apply { this.query = null }

        fun appendPath(newSegment: String?): Builder = appendEncodedPath(encode(newSegment ?: ""))

        fun appendEncodedPath(newSegment: String): Builder = apply {
            val current = path ?: ""
            path = when {
                current.isEmpty() -> if (newSegment.startsWith('/')) newSegment else "/$newSegment"
                current.endsWith('/') && newSegment.startsWith('/') -> current + newSegment.substring(1)
                current.endsWith('/') || newSegment.startsWith('/') -> current + newSegment
                else -> "$current/$newSegment"
            }
        }

        fun appendQueryParameter(key: String, value: String?): Builder = apply {
            val pair = encode(key) + "=" + encode(value ?: "")
            query = if (query.isNullOrEmpty()) pair else "$query&$pair"
        }

        fun build(): Uri = Uri(scheme, authority, path, query, fragment)
        override fun toString(): String = build().toString()
    }

    companion object {
        @JvmField
        val EMPTY: Uri = Uri(null, null, "", null, null)

        /** Same unreserved set as Android: letters, digits and `_-!.~'()*` */
        private const val UNRESERVED = "_-!.~'()*"

        @JvmStatic
        @JvmOverloads
        fun encode(s: String?, allow: String? = null): String? {
            if (s == null) return null
            val sb = StringBuilder(s.length + 16)
            for (ch in s) {
                if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || UNRESERVED.indexOf(ch) >= 0 ||
                    (allow != null && allow.indexOf(ch) >= 0)
                ) {
                    sb.append(ch)
                } else {
                    for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                        sb.append('%').append("0123456789ABCDEF"[(b.toInt() shr 4) and 0xF])
                            .append("0123456789ABCDEF"[b.toInt() and 0xF])
                    }
                }
            }
            return sb.toString()
        }

        @JvmStatic
        fun decode(s: String?): String? {
            if (s == null) return null
            return try {
                URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
            } catch (_: Exception) {
                s
            }
        }

        @JvmStatic
        fun parse(uriString: String): Uri {
            val s = uriString
            val schemeEnd = s.indexOf(':').takeIf { idx ->
                idx > 0 && s.substring(0, idx).all { it.isLetterOrDigit() || it in "+-." } && s[0].isLetter()
            } ?: -1
            val scheme = if (schemeEnd > 0) s.substring(0, schemeEnd) else null
            var rest = if (schemeEnd > 0) s.substring(schemeEnd + 1) else s

            var fragment: String? = null
            rest.indexOf('#').takeIf { it >= 0 }?.let { fragment = rest.substring(it + 1); rest = rest.substring(0, it) }

            if (scheme != null && !rest.startsWith("/")) {
                // opaque uri e.g. mailto:x@y
                return Uri(scheme, null, null, null, fragment, opaque = uriString)
            }
            var query: String? = null
            rest.indexOf('?').takeIf { it >= 0 }?.let { query = rest.substring(it + 1); rest = rest.substring(0, it) }

            var authority: String? = null
            if (rest.startsWith("//")) {
                val pathStart = rest.indexOf('/', 2).let { if (it < 0) rest.length else it }
                authority = rest.substring(2, pathStart)
                rest = rest.substring(pathStart)
            }
            return Uri(scheme, authority, rest, query, fragment)
        }

        @JvmStatic
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            parse("$scheme:${encode(ssp, "/@:")}" + (fragment?.let { "#" + encode(it) } ?: ""))

        @Suppress("unused")
        private fun formEncode(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    }
}
