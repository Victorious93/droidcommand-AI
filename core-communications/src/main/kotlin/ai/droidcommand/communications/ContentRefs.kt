// Victorious Framework: Android content:// references for the file-share engine. Pure JVM; no android.net.Uri.
//
// What Android's Storage Access Framework hands back is NOT a path:
//   tree:      content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCats
//   in a tree: content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FCats/document/primary%3ADCIM%2FCats%2Fa.jpg
//   one file:  content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCats%2Fa.jpg
// A document id ("primary:DCIM/Cats/a.jpg") has an encoded "/" inside it, so a lexical path check cannot be reused. This file
// parses a reference strictly, rebuilds ONE canonical spelling (so two spellings of the same file are the same file to the
// engine), and answers "is this document inside that tree?" by comparing decoded ids on "/" boundaries.
//
// Fail closed: anything unrecognised, odd or ambiguous parses to null and the engine denies it.
package ai.droidcommand.communications

data class ContentRef(
    val canonical: String,
    val authority: String,
    /** Set for `/tree/<id>` and `/tree/<id>/document/<id>`. */
    val treeId: String?,
    /** Set for `/document/<id>` and `/tree/<id>/document/<id>`. Null for a bare tree and for opaque media URIs. */
    val docId: String?,
)

object ContentRefs {
    /** Document providers a share may point into. Others (an app's own provider, a file:// URI) are refused. */
    val DEFAULT_AUTHORITIES: Set<String> = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
        "com.android.providers.media.photopicker",
        "media",
    )

    private const val SCHEME = "content://"
    private const val MAX_LEN = 2048

    fun isContent(raw: String) = raw.startsWith(SCHEME)

    fun parse(raw: String, authorities: Set<String> = DEFAULT_AUTHORITIES): ContentRef? {
        if (raw.length > MAX_LEN || !raw.startsWith(SCHEME)) return null
        val rest = raw.substring(SCHEME.length)
        if (rest.any { it == '?' || it == '#' || it == '\\' || it == '@' || it.code < 0x21 || it.code == 0x7f }) return null
        val authority = rest.substringBefore('/')
        if (authority !in authorities) return null
        val segs = rest.substringAfter('/', "").split('/')
        if (segs.isEmpty() || segs.any { it.isEmpty() }) return null
        val dec = segs.map { decode(it) ?: return null }
        var tree: String? = null
        var doc: String? = null
        val shape: List<Pair<String, Boolean>> = when {
            dec.size == 2 && dec[0] == "tree" -> {
                tree = dec[1]
                listOf("tree" to false, dec[1] to true)
            }
            dec.size == 4 && dec[0] == "tree" && dec[2] == "document" -> {
                tree = dec[1]
                doc = dec[3]
                listOf("tree" to false, dec[1] to true, "document" to false, dec[3] to true)
            }
            dec.size == 2 && dec[0] == "document" -> {
                doc = dec[1]
                listOf("document" to false, dec[1] to true)
            }
            // An opaque media URI is matched exactly and nothing more. A URI that merely starts like a SAF one but is the wrong
            // shape (for example "/tree/<id>/document" with no id) is malformed, not opaque.
            dec[0] == "tree" || dec[0] == "document" -> return null
            else -> dec.map { it to true }
        }
        // A "." or ".." component inside an id would let one id climb out of its folder, whatever the encoding.
        // Split on ':' as well as '/': "primary:../x" has the component ".." once the provider prefix is taken off.
        for (id in listOfNotNull(tree, doc)) if (id.split('/', ':').any { it == ".." || it == "." } || id.isEmpty()) return null
        if (shape.any { (v, isValue) -> isValue && (v == ".." || v == ".") }) return null
        val canonical = SCHEME + authority + "/" + shape.joinToString("/") { (v, isValue) -> if (isValue) encode(v) else v }
        return ContentRef(canonical, authority, tree, doc)
    }

    /** The canonical spelling, or null when [raw] is not an acceptable reference. */
    fun canonical(raw: String, authorities: Set<String> = DEFAULT_AUTHORITIES): String? = parse(raw, authorities)?.canonical

    /** The tree itself, without any `/document/…` part: what a tree grant is stored as. Null if [raw] has no tree. */
    fun treeOf(raw: String, authorities: Set<String> = DEFAULT_AUTHORITIES): ContentRef? {
        val r = parse(raw, authorities) ?: return null
        val t = r.treeId ?: return null
        return parse(SCHEME + r.authority + "/tree/" + encode(t), authorities)
    }

    /** True when the tree is a provider's whole root (for example `primary:`), which must never be shared as a folder. */
    fun isRoot(tree: ContentRef): Boolean = tree.treeId.let { it == null || it.endsWith(":") || it == "/" }

    /**
     * Is [request] a document inside [tree]? Same provider; the request names either no tree or the same one; its decoded id
     * starts with the tree's id on a "/" boundary (so `…/CatsPrivate` is NOT inside `…/Cats`); and, unless [subfolders],
     * it is a direct child. The tree itself counts as inside (so it can be browsed).
     */
    fun within(request: ContentRef, tree: ContentRef, subfolders: Boolean): Boolean {
        val root = tree.treeId ?: return false
        if (request.authority != tree.authority) return false
        if (request.treeId != null && request.treeId != root) return false
        val doc = request.docId ?: return request.treeId == root // a bare tree URI is the tree itself
        if (doc == root) return true
        val base = if (root.endsWith("/") || root.endsWith(":")) root else "$root/"
        if (!doc.startsWith(base)) return false
        return subfolders || !doc.substring(base.length).contains('/')
    }

    private fun decode(s: String): String? {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 3 > s.length) return null
                val h = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                out.write(h)
                i += 3
            } else {
                if (c.code > 0x7e) return null
                out.write(c.code)
                i++
            }
        }
        val bytes = out.toByteArray()
        if (bytes.any { it.toInt() == 0 || it.toInt() in 1..0x1f || it.toInt() == 0x7f || it.toInt() == '\\'.code || it.toInt() == '%'.code }) return null
        return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }

    private fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
            }
        }
        return sb.toString()
    }
}
