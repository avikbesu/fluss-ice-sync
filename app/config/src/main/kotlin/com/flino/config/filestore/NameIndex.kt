package com.flino.config.filestore

import java.util.concurrent.ConcurrentHashMap

/**
 * An in-memory `name (lowercased) -> id` map, built once at startup by
 * scanning the config directory and kept up to date on every write within
 * *this* process -- the build prompt's "maintaining an in-memory index
 * built at startup and kept in sync with writes" option, chosen over
 * scanning every file on every write, since name-uniqueness is checked on
 * every `POST`.
 *
 * **Scoped to a single process.** With more than one replica sharing a
 * volume (see the README's ReadWriteMany section), this index does not
 * see writes made by a sibling replica -- two replicas could race and
 * both accept a create for the same name. This is a known, documented gap
 * for the multi-replica case, not solved here; see the README's "edge
 * cases not handled".
 */
class NameIndex {
    private val nameToId = ConcurrentHashMap<String, String>()

    /** True if [name] is already registered to some *other* id than [excludingId] (itself renaming to its own name is not a conflict). */
    fun isTaken(name: String, excludingId: String?): Boolean {
        val owner = nameToId[key(name)] ?: return false
        return owner != excludingId
    }

    fun register(name: String, id: String) {
        nameToId[key(name)] = id
    }

    /** Call after a successful update that changed [oldName] to [newName] so a freed-up old name becomes available again. */
    fun rename(oldName: String?, newName: String, id: String) {
        if (oldName != null && !oldName.equals(newName, ignoreCase = true)) {
            nameToId.remove(key(oldName), id)
        }
        register(newName, id)
    }

    private fun key(name: String) = name.lowercase()
}
