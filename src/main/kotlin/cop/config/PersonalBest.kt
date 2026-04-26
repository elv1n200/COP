package cop.config

/**
 * Port of NoammAddons PersonalBest (com.github.noamm9.config.PersonalBest).
 *
 * Keeps a disk-persisted map of PB values keyed by arbitrary strings. Modules call
 * [checkAndSetPB] after a timed action; the helper decides whether the new value beats
 * the stored one, writes it, and returns whether this run was a new PB.
 */
object PersonalBest {
    // Uses COP's existing configMap() which persists to config/cop/personal_bests
    private val pbs by configMap<String, Double>("personal_bests")

    /**
     * Compare [value] against the stored PB for [key].
     * @return true iff this beats (or seeds) the PB and was saved.
     */
    fun checkAndSetPB(key: String, value: Number, lowerIsBetter: Boolean = true): Boolean {
        val v = value.toDouble()
        val current = pbs[key]
        val isNew = when {
            current == null -> true
            lowerIsBetter && v < current -> true
            !lowerIsBetter && v > current -> true
            else -> false
        }
        if (isNew) pbs[key] = v
        return isNew
    }

    fun getPB(key: String): Double? = pbs[key]

    fun clearPB(key: String) {
        pbs.remove(key)
    }
}
