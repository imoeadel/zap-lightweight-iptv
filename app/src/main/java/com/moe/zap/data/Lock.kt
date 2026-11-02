package com.moe.zap.data

import com.moe.zap.core.Kind
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Which provider categories are hidden or PIN-locked. A hidden or still-locked category disappears from
 * All, Recently added, Favorites and search too, so its titles cannot be reached by another route.
 */
object Lock {
    enum class State { VISIBLE, HIDDEN, LOCKED }

    private val unlocked = HashSet<String>()

    /** Changes whenever what is visible changes, so open lists know to reload. */
    @Volatile var version = 0
        private set
    private var cachedExclude = HashMap<Kind, String>()

    private fun key(kind: Kind, id: String) = "${kind.code}:$id"

    private fun set(name: String) = Repo.prefs.getStringSet(name, emptySet()).orEmpty()

    @Synchronized
    fun stateOf(kind: Kind, id: String): State {
        val k = key(kind, id)
        return when {
            k in set("hidden") -> State.HIDDEN
            k in set("locked") -> State.LOCKED
            else -> State.VISIBLE
        }
    }

    @Synchronized
    fun setState(kind: Kind, id: String, state: State) {
        val k = key(kind, id)
        val hidden = set("hidden").toMutableSet().apply { remove(k) }
        val locked = set("locked").toMutableSet().apply { remove(k) }
        if (state == State.HIDDEN) hidden += k
        if (state == State.LOCKED) locked += k
        Repo.prefs.edit().putStringSet("hidden", hidden).putStringSet("locked", locked).apply()
        cachedExclude.clear()
        version++
    }

    @Synchronized
    fun isLockedNow(kind: Kind, id: String) = stateOf(kind, id) == State.LOCKED && key(kind, id) !in unlocked

    /** Unlocks until the app closes. */
    @Synchronized
    fun unlock(kind: Kind, id: String) {
        unlocked += key(kind, id)
        cachedExclude.clear()
        version++
    }

    @Synchronized
    fun forgetSession() {
        unlocked.clear()
        cachedExclude.clear()
    }

    /** SQL excluding this section's hidden and still-locked categories, or nothing. */
    @Synchronized
    fun excludeClause(kind: Kind): String = cachedExclude.getOrPut(kind) {
        val prefix = "${kind.code}:"
        val ids = (set("hidden") + (set("locked") - unlocked))
            .filter { it.startsWith(prefix) }
            .map { "'" + it.removePrefix(prefix).replace("'", "''") + "'" }
        if (ids.isEmpty()) "" else "AND NOT (item.kind=${kind.code} AND item.cat IN (${ids.joinToString(",")}))"
    }

    fun hasPin() = Repo.prefs.contains("pinHash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        Repo.prefs.edit().putString("pinSalt", hex(salt)).putString("pinHash", hash(salt, pin)).apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = Repo.prefs.getString("pinSalt", null) ?: return false
        return hash(unhex(salt), pin) == Repo.prefs.getString("pinHash", null)
    }

    private fun hash(salt: ByteArray, pin: String): String =
        hex(MessageDigest.getInstance("SHA-256").digest(salt + pin.toByteArray()))

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) .toByte() }
}
