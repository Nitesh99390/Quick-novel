package android.net

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Minimal JVM re-implementation of android.net.Uri – only the surface used by the
 * QuickNovel providers (encode, parse, buildUpon, Builder.appendPath/appendQueryParameter).
 */
class Uri private constructor(
    val scheme: String?,
    val encodedAuthority: String?,
    val encodedPath: String,
    val encodedQuery: String?,
    val encodedFragment: String?,
) {
    val host: String? get() = encodedAuthority?.substringAfter('@')?.substringBefore(':')
    val path: String get() = decode(encodedPath)
    val query: String? get() = encodedQuery?.let { decode(it) }
    val fragment: String? get() = encodedFragment?.let { decode(it) }
    val pathSegments: List<String> get() = encodedPath.split('/').filter { it.isNotEmpty() }.map { decode(it) }
    val lastPathSegment: String? get() = pathSegments.lastOrNull()

    fun getQueryParameter(key: String): String? =
        encodedQuery?.split('&')?.map { it.split('=', limit = 2) }
            ?.firstOrNull { decode(it[0]) == key }?.let { decode(it.getOrElse(1) { "" }) }

    fun getQueryParameterNames(): Set<String> =
        encodedQuery?.split('&')?.filter { it.isNotEmpty() }?.map { decode(it.substringBefore('=')) }?.toSet() ?: emptySet()

    fun buildUpon(): Builder = Builder().also {
        it.scheme = scheme
        it.authority = encodedAuthority
        it.path = StringBuilder(encodedPath)
        it.query = encodedQuery?.let { q -> StringBuilder(q) }
        it.fragment = encodedFragment
    }

    override fun toString(): String = buildString {
        if (scheme != null) append(scheme).append(':')
        if (encodedAuthority != null) append("//").append(encodedAuthority)
        append(encodedPath)
        if (!encodedQuery.isNullOrEmpty()) append('?').append(encodedQuery)
        if (encodedFragment != null) append('#').append(encodedFragment)
    }

    override fun equals(other: Any?): Boolean = other is Uri && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()

    class Builder {
        internal var scheme: String? = null
        internal var authority: String? = null
        internal var path: StringBuilder = StringBuilder()
        internal var query: StringBuilder? = null
        internal var fragment: String? = null

        fun scheme(value: String?) = apply { scheme = value }
        fun authority(value: String?) = apply { authority = value?.let { encode(it, "@:") } }
        fun encodedAuthority(value: String?) = apply { authority = value }
        fun path(value: String?) = apply { path = StringBuilder(value?.let { encode(it, "/") } ?: "") }
        fun encodedPath(value: String?) = apply { path = StringBuilder(value ?: "") }
        fun fragment(value: String?) = apply { fragment = value?.let { encode(it) } }
        fun encodedQuery(value: String?) = apply { query = value?.let { StringBuilder(it) } }
        fun clearQuery() = apply { query = null }

        fun appendPath(segment: String?) = appendEncodedPath(encode(segment ?: ""))

        fun appendEncodedPath(segment: String) = apply {
            if (path.isEmpty() || path.last() != '/') path.append('/')
            path.append(segment.trimStart('/'))
        }

        fun appendQueryParameter(key: String?, value: String?) = apply {
            val q = query ?: StringBuilder().also { query = it }
            if (q.isNotEmpty()) q.append('&')
            q.append(encode(key ?: "")).append('=').append(encode(value ?: ""))
        }

        fun build(): Uri = Uri(scheme, authority, path.toString(), query?.toString(), fragment)
        override fun toString(): String = build().toString()
    }

    companion object {
        @JvmField
        val EMPTY: Uri = Uri(null, null, "", null, null)

        private const val HEX = "0123456789ABCDEF"
        private const val UNRESERVED = "_-!.~'()*"

        @JvmStatic
        @JvmOverloads
        fun encode(s: String, allow: String? = null): String {
            val out = StringBuilder(s.length)
            for (b in s.toByteArray(StandardCharsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || UNRESERVED.indexOf(c) >= 0 || (allow != null && allow.indexOf(c) >= 0)) {
                    out.append(c)
                } else {
                    val v = b.toInt() and 0xff
                    out.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0f])
                }
            }
            return out.toString()
        }

        @JvmStatic
        fun decode(s: String): String = try {
            URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            s
        }

        @JvmStatic
        fun parse(uriString: String): Uri {
            var rest = uriString
            var fragment: String? = null
            rest.indexOf('#').takeIf { it >= 0 }?.let { fragment = rest.substring(it + 1); rest = rest.substring(0, it) }
            var query: String? = null
            rest.indexOf('?').takeIf { it >= 0 }?.let { query = rest.substring(it + 1); rest = rest.substring(0, it) }
            var scheme: String? = null
            Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(rest)?.let {
                scheme = it.groupValues[1]; rest = rest.substring(it.value.length)
            }
            var authority: String? = null
            if (rest.startsWith("//")) {
                rest = rest.substring(2)
                val slash = rest.indexOf('/')
                if (slash >= 0) {
                    authority = rest.substring(0, slash); rest = rest.substring(slash)
                } else {
                    authority = rest; rest = ""
                }
            }
            return Uri(scheme, authority, rest, query, fragment)
        }

        @JvmStatic
        fun fromParts(scheme: String, ssp: String, fragment: String?): Uri =
            parse("$scheme:${encode(ssp, "/:@")}" + (fragment?.let { "#" + encode(it) } ?: ""))
    }
}
