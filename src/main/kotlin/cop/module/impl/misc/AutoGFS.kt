package cop.module.impl.misc

import cop.api.events.ChatEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Location
import cop.api.skyblock.SkyblockPlayer
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.dungeon.Floor
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.PlayerUtils

object AutoGFS : Module( // untested
    "Auto GFS",
    desc = "Automatically refills certain items from your sacks."
) {
    private val itemsDropdown by text("Items to refill")
    private val pearls by switch("Pearls").childOf(::itemsDropdown)
    private val booms by switch("Super booms").childOf(::itemsDropdown)
    private val jerries by switch("Inflatable Jerries").childOf(::itemsDropdown)
    private val leaps by switch("Spirit Leaps").childOf(::itemsDropdown)
    private val twilight by switch(
        "Twilight Arrow Poison",
        desc = "Refills Twilight Arrow Poison at selected M7 transitions."
    ).childOf(::itemsDropdown)

    private val twilightTriggers by text("Twilight triggers").childOf(::twilight)
    private val twilightAfterLightning by switch(
        "After Storm lightning",
        true,
        desc = "Refills once after Storm's lightning line when playing Archer."
    ).childOf(::twilightTriggers)
    private val twilightAtCore by switch(
        "At Core",
        desc = "Refills when Core opens for Archer/Berserk."
    ).childOf(::twilightTriggers)
    private val twilightAfterRelics by switch(
        "After M7 relics",
        true,
        desc = "Refills at the P5 transition for non-DPS classes."
    ).childOf(::twilightTriggers)
    private val twilightAmount by slider(
        "Twilight target",
        8,
        4,
        16,
        1,
        desc = "Target amount to keep in inventory."
    ).childOf(::twilight)

    private val mode by selector("Mode", "Amount", arrayListOf("Amount", "Time"))
    private val amount by slider("Amount", 50, 5, 95, 5, unit = "%").childOf(::mode) { it.selected == "Amount" }
    private val time by slider("Time", 5, 1, 60, 1, unit = "s").childOf(::mode) { it.selected == "Time" }

    private val dungeonsOnly by switch("Dungeons only", desc = "Only refill items when in dungeons.")

    private var tickCount = 0
    private var refillCursor = 0
    private var stormLightningHandled = false
    private var lastTwilightCommandAt = 0L
    private var lastRefillCommandAt = 0L
    private val emptySacks = hashSetOf<String>()

    init {
        on<WorldEvent.Change> {
            refillCursor = 0
            stormLightningHandled = false
            lastTwilightCommandAt = 0L
            lastRefillCommandAt = 0L
            emptySacks.clear()
        }

        on<ChatEvent.PacketClient> {
            val raw = message.noControlCodes.trim()
            RefillItem.entries.firstOrNull { raw == emptySackMessage(it.itemName) }?.let {
                emptySacks += it.sackName
            }
            if (raw == emptySackMessage(TWILIGHT_ITEM_NAME)) emptySacks += TWILIGHT_SACK_NAME

            if (!twilight || Dungeon.floor != Floor.M7 || !Dungeon.inBoss) return@on

            val clazz = Dungeon.currentDungeonPlayer.clazz
            val dps = clazz == DungeonClass.Archer || clazz == DungeonClass.Berserk
            when {
                twilightAtCore && dps && raw == CORE_OPEN_MESSAGE -> pullTwilight()
                twilightAfterRelics && clazz != DungeonClass.Unknown && !dps &&
                    WITHER_KING_TRANSITION.matches(raw) -> pullTwilight()
                twilightAfterLightning && !stormLightningHandled && clazz == DungeonClass.Archer &&
                    STORM_LIGHTNING.matches(raw) -> {
                    stormLightningHandled = true
                    pullTwilight()
                }
            }
        }

        on<TickEvent.End> {
            if (dungeonsOnly && !Dungeon.inDungeons) return@on
            if (Dungeon.isDead || !Location.inSkyblock || mc.screen != null) return@on
            if (!SkyblockPlayer.canUseCommands) {
                tickCount = 10_000
                return@on
            }

            if (++tickCount < when (mode.selected) {
                    "Amount" -> 20
                    "Time" -> time * 20
                    else -> Int.MAX_VALUE
                }
            ) return@on
            tickCount = 0

            when (mode.selected) {
                "Amount" -> refillNext { it.shouldRefill() }
                "Time" -> refillNext { it.enabled }
            }
        }
    }

    private fun pullTwilight() {
        if (TWILIGHT_SACK_NAME in emptySacks) return
        if (!SkyblockPlayer.canUseCommands || mc.screen != null || Dungeon.isDead) return
        val now = System.currentTimeMillis()
        if (now - lastTwilightCommandAt < 2_000L || now - lastRefillCommandAt < 750L) return

        val current = PlayerUtils.getItemsAmount("TWILIGHT_ARROW_POISON")
        val missing = (twilightAmount - current).coerceAtLeast(0)
        if (missing == 0) return

        lastTwilightCommandAt = now
        lastRefillCommandAt = now
        ChatUtils.command("gfs $TWILIGHT_SACK_NAME $missing")
    }

    private fun isBelowPercentage(n: Int, max: Int) = n < (amount / 100.0) * max

    private fun refillNext(predicate: (RefillItem) -> Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastRefillCommandAt < 750L) return
        repeat(RefillItem.entries.size) {
            val item = RefillItem.entries[refillCursor]
            refillCursor = (refillCursor + 1) % RefillItem.entries.size
            if (item.sackName in emptySacks) return@repeat
            if (predicate(item)) {
                if (item.refill()) {
                    lastRefillCommandAt = now
                    return
                }
            }
        }
    }

    private enum class RefillItem(
        val maxStack: Int,
        val itemId: String,
        val sackName: String,
        val itemName: String
    ) {
        PEARL(16, "ENDER_PEARL", "ender_pearl", "Ender Pearls"),
        BOOM(64, "SUPERBOOM_TNT", "superboom_tnt", "Superboom TNT"),
        JERRY(64, "INFLATABLE_JERRY", "inflatable_jerry", "Inflatable Jerries"),
        LEAP(16, "SPIRIT_LEAP", "spirit_leap", "Spirit Leaps");

        val enabled get() = when(this) {
            PEARL -> pearls
            BOOM -> booms
            JERRY -> jerries
            LEAP -> leaps
        }

        fun shouldRefill(): Boolean {
            return enabled && isBelowPercentage(PlayerUtils.getItemsAmount(itemId), maxStack)
        }

        fun refill(): Boolean {
            if (PlayerUtils.getItemsAmount(itemId) >= maxStack) return false
            PlayerUtils.fillItemFromSack(itemId, maxStack, sackName)
            return true
        }
    }

    private fun emptySackMessage(itemName: String) = "You have no $itemName in your Sacks!"

    private const val TWILIGHT_SACK_NAME = "twilight_arrow_poison"
    private const val TWILIGHT_ITEM_NAME = "Twilight Arrow Poison"
    private const val CORE_OPEN_MESSAGE = "The Core entrance is opening!"
    private val WITHER_KING_TRANSITION =
        Regex("^\\[BOSS] Wither King: I no longer wish to fight, but I know that will not stop you\\.$")
    private val STORM_LIGHTNING =
        Regex("^\\[BOSS] Storm: (?:ENERGY HEED MY CALL|THUNDER LET ME BE YOUR CATALYST)!$")
}
