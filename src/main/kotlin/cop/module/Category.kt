package cop.module

enum class Category {
    DUNGEON,
    RENDER,
    PLAYER,
    MISC,
    MINING,

    /** Catch-all column for modules registered by third-party addons (see
     *  [cop.api.addon.CopAddon]). Addon modules default here unless they set a
     *  category explicitly. Kept LAST so existing categories keep their ordinals
     *  — the ClickGUI positions category columns by ordinal and persists their
     *  positions keyed by this enum, so inserting in the middle would shuffle
     *  everyone's saved layout. */
    ADDON,
}
