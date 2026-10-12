package ai.droidcommand.app.clipboard

import java.util.Base64

/** One clip. Ids are unique within one [ClipboardHistory] instance and are not persisted. */
data class Clip(val id: Long, val text: String)

/**
 * Where private clips live. [load] returns null when stored data exists but cannot be read. [save] throws if the
 * write fails, so [ClipboardHistory] only changes its in-memory lists after the store has accepted the change.
 */
interface PrivateClipStorage {
    fun load(): List<String>?
    fun save(texts: List<String>)
}

/**
 * The two clipboards. Public clips live in memory and are lost when the process stops. Private clips go through
 * [storage] before the in-memory list changes, so a failed write leaves both lists as they were.
 *
 * [publicLimit] is read on every change, so a new limit from Advanced settings applies at once; lowering it drops
 * the oldest public clips.
 *
 * If the stored private data cannot be read, [privateUnreadable] is true and nothing can be moved into the private
 * list: that would overwrite data the user may still want. [discardPrivate] is the explicit way out.
 */
class ClipboardHistory(
    private val publicLimit: () -> Int,
    private val storage: PrivateClipStorage,
    val privateLimit: Int = DEFAULT_PRIVATE_LIMIT,
) {
    private var nextId = 1L
    private val publicList = ArrayList<Clip>() // newest first
    private val privateList = ArrayList<Clip>() // newest first

    var privateUnreadable: Boolean = false
        private set

    init {
        val stored = storage.load()
        if (stored == null) {
            privateUnreadable = true
        } else {
            stored.take(privateLimit).forEach { privateList.add(Clip(nextId++, it)) }
        }
    }

    fun publicClips(): List<Clip> {
        trimPublic()
        return publicList.toList()
    }

    fun privateClips(): List<Clip> = privateList.toList()

    /** Adds text to the public list. Blank text, and an immediate repeat of the newest public clip, are ignored. */
    fun addPublic(text: String): Boolean {
        if (text.isBlank() || publicList.firstOrNull()?.text == text) return false
        publicList.add(0, Clip(nextId++, text))
        trimPublic()
        return true
    }

    fun deletePublic(id: Long) {
        publicList.removeAll { it.id == id }
    }

    fun clearPublic() {
        publicList.clear()
    }

    /**
     * Moves a public clip into the private list. Returns false and changes nothing when the clip no longer exists,
     * the private store is unreadable, the private list is full, or the write fails.
     */
    fun moveToPrivate(id: Long): Boolean {
        val clip = publicList.firstOrNull { it.id == id } ?: return false
        if (privateUnreadable || privateList.size >= privateLimit) return false
        if (!persist(listOf(clip.text) + privateList.map { it.text })) return false
        publicList.remove(clip)
        privateList.add(0, Clip(nextId++, clip.text))
        return true
    }

    fun deletePrivate(id: Long): Boolean {
        if (privateUnreadable) return false
        if (!persist(privateList.filter { it.id != id }.map { it.text })) return false
        privateList.removeAll { it.id == id }
        return true
    }

    /** Erases every private clip, including an unreadable store. Whatever was stored is overwritten. */
    fun discardPrivate(): Boolean {
        if (!persist(emptyList())) return false
        privateList.clear()
        privateUnreadable = false
        return true
    }

    private fun trimPublic() {
        val limit = publicLimit()
        while (publicList.size > limit) publicList.removeAt(publicList.lastIndex)
    }

    private fun persist(texts: List<String>): Boolean = try {
        storage.save(texts)
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        const val DEFAULT_PRIVATE_LIMIT = 50
    }
}

/** Private clips are stored as Base64(UTF-8) parts joined by commas, so no clip text can break the format. */
object PrivateClipCodec {
    fun encode(texts: List<String>): String =
        texts.joinToString(",") { Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }

    /** Null when [raw] is not in the format [encode] writes. */
    fun decode(raw: String): List<String>? {
        if (raw.isEmpty()) return emptyList()
        return try {
            raw.split(',').map { String(Base64.getDecoder().decode(it), Charsets.UTF_8) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
