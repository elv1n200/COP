package cop.module.settings.impl

/** Pure key model used to migrate the legacy nested HUD config format. */
internal data class HudSettingKey(
    val name: String,
    val jsonName: String,
)

/**
 * The old HUD writer ignored [HudSettingKey.jsonName] and stored display names.
 * A newly written object always contains every custom JSON key, so an object
 * with none of them is unambiguously legacy data.
 */
internal fun usesLegacyHudSettingKeys(
    savedKeys: Set<String>,
    settings: List<HudSettingKey>,
): Boolean {
    val customKeys = settings.filterNot { it.name.equals(it.jsonName, ignoreCase = true) }
    return customKeys.isNotEmpty() && customKeys.none { setting ->
        savedKeys.any { it.equals(setting.jsonName, ignoreCase = true) }
    }
}

/**
 * Resolve a persisted key to its setting index. Legacy duplicate display names
 * deliberately select the last setting because Gson's old writer used
 * last-write-wins for those exact collisions.
 */
internal fun resolveHudSettingIndex(
    savedKey: String,
    settings: List<HudSettingKey>,
    legacy: Boolean,
): Int? {
    val index = if (legacy) {
        settings.indexOfLast { it.name.equals(savedKey, ignoreCase = true) }
    } else {
        settings.indexOfFirst { it.jsonName.equals(savedKey, ignoreCase = true) }
    }
    return index.takeIf { it >= 0 }
}
