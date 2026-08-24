package cop.module.impl.dungeon.cheats

import cop.api.events.ChatEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.StringUtils.noControlCodes

/**
 * Uses the class ultimate (the dungeon drop-key action) at deterministic boss
 * transitions. Every transition is independently configurable so the module is
 * useful on lower floors without forcing an F7-oriented setup.
 */
object DungeonAbilities : Module(
    "Dungeon Abilities",
    area = Island.Dungeon(inBoss = true),
    desc = "Automatically uses class ultimates at selected dungeon boss transitions."
) {
    private val maxorEnrage by switch(
        "Maxor enrage",
        true,
        desc = "Uses Healer or Tank ultimate when Maxor becomes enraged."
    )
    private val goldorFactory by switch(
        "Goldor factory",
        true,
        desc = "Uses Healer or Tank ultimate after Goldor's factory is destroyed."
    )
    private val lividEntry by switch(
        "Livid entry",
        true,
        desc = "Uses Healer or Tank ultimate when the Livid fight starts."
    )
    private val sadanGiants by switch(
        "Sadan giants",
        true,
        desc = "Uses any class ultimate when Sadan releases his giants."
    )

    private val healer by switch("Healer", true, desc = "Allows automatic Healer ultimates.")
    private val tank by switch("Tank", true, desc = "Allows automatic Tank ultimates.")
    private val archer by switch("Archer", true, desc = "Allows automatic Archer ultimates.")
    private val berserk by switch("Berserk", true, desc = "Allows automatic Berserk ultimates.")
    private val mage by switch("Mage", true, desc = "Allows automatic Mage ultimates.")

    private var lastTrigger = ""
    private var lastTriggerAt = 0L

    init {
        on<WorldEvent.Change> {
            lastTrigger = ""
            lastTriggerAt = 0L
        }

        on<ChatEvent.PacketClient> {
            if (!Dungeon.inBoss || Dungeon.isDead || mc.screen != null) return@on

            val plain = message.noControlCodes
            val allowedClasses = triggerClasses(plain) ?: return@on
            val clazz = Dungeon.currentDungeonPlayer.clazz
            if (clazz !in allowedClasses || !classEnabled(clazz)) return@on

            // Hypixel can resend boss dialogue during short reconnects. Do not
            // turn one transition into several drop-key actions.
            val now = System.currentTimeMillis()
            if (plain == lastTrigger && now - lastTriggerAt < 5_000L) return@on
            lastTrigger = plain
            lastTriggerAt = now

            mc.player?.drop(false)
        }
    }

    private fun triggerClasses(message: String): Set<DungeonClass>? = when {
        maxorEnrage && message == "⚠ Maxor is enraged! ⚠" -> SUPPORT_CLASSES
        goldorFactory && message in GOLDOR_LINES -> SUPPORT_CLASSES
        lividEntry && message == LIVID_LINE -> SUPPORT_CLASSES
        sadanGiants && message == SADAN_LINE -> ALL_CLASSES
        else -> null
    }

    private fun classEnabled(clazz: DungeonClass): Boolean = when (clazz) {
        DungeonClass.Healer -> healer
        DungeonClass.Tank -> tank
        DungeonClass.Archer -> archer
        DungeonClass.Berserk -> berserk
        DungeonClass.Mage -> mage
        DungeonClass.Unknown -> false
    }

    private val SUPPORT_CLASSES = setOf(DungeonClass.Healer, DungeonClass.Tank)
    private val ALL_CLASSES = setOf(
        DungeonClass.Healer,
        DungeonClass.Tank,
        DungeonClass.Archer,
        DungeonClass.Berserk,
        DungeonClass.Mage
    )
    private val GOLDOR_LINES = setOf(
        "[BOSS] Goldor: You have done it, you destroyed the factory…",
        "[BOSS] Goldor: You have done it, you destroyed the factory..."
    )
    private const val LIVID_LINE =
        "[BOSS] Livid: I respect you for making it to here, but I'll be your undoing."
    private const val SADAN_LINE = "[BOSS] Sadan: My giants! Unleashed!"
}
