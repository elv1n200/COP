package cop.api.commands

import cop.CopMod.mc
import cop.api.commands.internal.BaseCommand
import cop.api.commands.internal.GreedyString
import cop.api.events.core.EventBus
import cop.api.skyblock.Island
import cop.api.skyblock.Location
import cop.api.skyblock.Location.currentArea
import cop.api.skyblock.Location.currentServer
import cop.api.skyblock.Location.inSkyblock
import cop.api.skyblock.Location.subarea
import cop.api.skyblock.dungeon.Dungeon
//import cop.api.skyblock.dungeon.map.utils.ScanUtils.currentRoom
import cop.module.ModuleManager
import cop.module.impl.misc.Chat
import cop.module.impl.render.ClickGui.clickGui
import cop.utils.ChatUtils.command
import cop.utils.ChatUtils.literal
import cop.utils.ChatUtils.modMessage
import cop.utils.DiagnosticsReport
import cop.utils.Scheduler.scheduleLoop
import cop.utils.WorldUtils
import cop.utils.WorldUtils.day
import cop.utils.skyblock.player.MovementUtils.hold
import cop.utils.skyblock.player.MovementUtils.isMoving
import cop.utils.ticker
import cop.utils.ui.hud.HudManager
import cop.utils.ui.screens.UIScreen.Companion.open
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import cop.api.skyblock.dungeon.Dungeon.currentRoom
import cop.utils.StringUtils.capitaliseFirst
import cop.utils.addVec
import cop.utils.skyblock.player.RotationUtils.rotate
import kotlin.collections.sortedBy

object CopCommand {
    val command = BaseCommand("cop", "cope") {
        open(clickGui)
    }

    val devCommand = BaseCommand("copdev")

    private fun warpTicker(cmd: String) = ticker {
        action { command("warp $cmd") }
        action(80) { command("warp hub") }
        delay(80)
    }

    private fun antiAfkTicker(delay: Int) = ticker {
        action { mc.options.keyLeft.hold(1) }
        action(delay) { mc.options.keyRight.hold(1) }
        delay(delay)
    }

    init {

        with(devCommand) {
            "copy" { string: GreedyString ->
                mc.keyboardHandler.clipboard = string.string
            }

            // Dumps the currently-open chest GUI: title + every non-empty slot
            // in the top three rows with its plain item name and lore. Used to
            // verify the AutoCroesus parser against actual Hypixel data.
            // Output goes to the game log (latest.log) so multi-line tooltips
            // don't blow up the chat.
            "croesusdump" {
                val screen = mc.screen as? net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<*>
                if (screen == null) {
                    modMessage("&cOpen the Croesus / run GUI first.")
                    return@invoke
                }
                val title = screen.title.string
                val menu = screen.menu
                val end = (menu.slots.size - 36).coerceAtMost(27)
                val log = cop.CopMod.logger
                log.info("[cop] CroesusDump — title=\"$title\", slots=${menu.slots.size}")
                for (i in 0 until end) {
                    val stack = menu.slots.getOrNull(i)?.item ?: continue
                    if (stack.isEmpty) continue
                    val name = stack.hoverName.string
                    val lore = stack.get(net.minecraft.core.component.DataComponents.LORE)
                        ?.lines?.map { it.string } ?: emptyList()
                    log.info("[cop]   slot=$i name=\"$name\"")
                    for ((j, line) in lore.withIndex()) log.info("[cop]     lore[$j]=\"$line\"")
                }
                modMessage("&aDumped GUI \"$title\" to latest.log (${end} top slots scanned).")
            }

            // Phase-1 sanity check for the Auto Croesus price client.
            // Usage:
            //   /copdev pricetest <ITEM_ID or display name>          (regular item)
            //   /copdev pricetest book <ENCHANT_NAME> <level>         (enchant book)
            // Examples:
            //   /copdev pricetest HYPERION
            //   /copdev pricetest book ULTIMATE_COMBO 5
            //   /copdev pricetest book SHARPNESS 7
            // Ultimate enchants (Combo, Wise, ...) need the ULTIMATE_ prefix —
            // SkyCofl uses the same names the NBT does (lowercased), and our
            // chest-scanner in later phases will read the NBT key directly.
            "pricetest" { query: GreedyString ->
                val pc = cop.utils.skyblock.PriceClient
                val parts = query.string.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.isEmpty()) {
                    modMessage("&cUsage: /copdev pricetest <id>  &7or  &c/copdev pricetest book <ENCHANT> <lvl>")
                    return@invoke
                }
                val ageLabel = if (pc.isLoaded) "${pc.ageMs / 1000}s" else "never"

                // --- enchant-book form ---
                // Enchant books live on the BAZAAR with ids ENCHANTMENT_<NAME>_<LVL>
                // (ultimates keep the ULTIMATE_ prefix in the name). The bulk
                // refresh already populates all of them — we just look up.
                if (parts[0].equals("book", true)) {
                    if (parts.size < 3) {
                        modMessage("&cUsage: /copdev pricetest book <ENCHANT_NAME> <level>")
                        return@invoke
                    }
                    val name = parts[1].uppercase()
                    val level = parts[2].toIntOrNull() ?: run {
                        modMessage("&cLevel must be an integer.")
                        return@invoke
                    }
                    modMessage("&7Fetching enchant book ${name} ${level} (cache age=$ageLabel)...")
                    pc.refreshIfStale {
                        mc.execute {
                            val price = pc.getEnchantBookPrice(name, level)
                            pc.lastError?.let { modMessage("&eBulk fetch issues: &f$it") }
                            modMessage(
                                "&bPrice for &fENCHANTMENT_${name}_${level}&7:" +
                                    "\n  &7bazaar sell: " + (price?.let { "&a${pc.formatPrice(it)}" } ?: "&8n/a")
                            )
                        }
                    }
                    return@invoke
                }

                // --- regular item form ---
                val raw = parts.joinToString(" ")
                modMessage("&7Fetching prices (cache age=$ageLabel)...")
                pc.refreshIfStale {
                    val id = pc.resolveItemId(raw) ?: raw.uppercase().replace(' ', '_')
                    pc.fetchLowestBin(id) { lb ->
                        mc.execute {
                            val bz = pc.getBazaarSell(id)
                            val best = pc.getPrice(id)
                            pc.lastError?.let { modMessage("&eBulk fetch issues: &f$it") }
                            modMessage(
                                "&bPrice for &f$id&7:" +
                                    "\n  &7bazaar sell: " + (bz?.let { "&a${pc.formatPrice(it)}" } ?: "&8n/a") +
                                    "\n  &7lowest BIN:  " + (lb?.let { "&a${pc.formatPrice(it)}" } ?: "&8n/a") +
                                    "\n  &7best:        " + (best?.let { "&e${pc.formatPrice(it)}" } ?: "&cunknown")
                            )
                        }
                    }
                }
            }

            "simulate" { message: GreedyString ->
                EventBus.onPacketReceived(ClientboundSystemChatPacket(literal(message.string), false))
                modMessage("simulated: ${message.string}")
            }

            "currentroom" {
                currentRoom?.let { room ->
                    val player = mc.player!!
                    val currentComp = room.roomComponents.minByOrNull { comp ->
                        val dx = player.x - comp.x
                        val dz = player.z - comp.z
                        dx * dx + dz * dz
                    }

                    val componentsString = room.roomComponents.mapIndexed { index, comp ->
                        val curr = if (comp == currentComp) "&a->&f" else "   "
                        "$curr &7$index: ${comp.vec2} &7| &f${comp.core}"
                    }.joinToString("\n")


                    val msg = listOf(
                        "&e${room.data.name} &7(${room.data.type})",
                        "&7|&fState: &7${room.state}",
                        "&7|&fCorner: &7${room.clayPos.x}, ${room.clayPos.y}, ${room.clayPos.z}",
                        "&7|&fRotation: &7${room.rotation} (${room.rotation.deg})",
                        "&7|&fComponents:",
                        componentsString
                    ).joinToString("\n")

                    modMessage(msg, prefix = "")
                }
            }

            "relative" {
                mc.hitResult?.let {
                    if (it !is BlockHitResult) return@let
                    currentRoom?.getRelativeCoords(it.blockPos)?.let { vec2 ->
                        modMessage("Relative coords: ${vec2.x}, ${vec2.z}")
                    }
                    currentRoom?.getRelativeCoords(Vec3(it.blockPos))?.let { vec2 ->
                        modMessage("Relative coords: ${vec2.x}, ${vec2.z}")
                    }

                }
            }

            "area" {
                modMessage("Area: $currentArea, Sub: $subarea, Server: $currentServer, Floor: ${Dungeon.floor?.name}")
            }

            "featurelist" { md: Boolean? ->
                val featureList = StringBuilder()

                for ((category, modulesInCategory) in ModuleManager.modules.groupBy { it.category }.entries) {
                    val categoryName = category.name.capitaliseFirst()

                    if (md == true) {
                        featureList.appendLine("<details>")
                        featureList.appendLine("<summary><b>$categoryName</b></summary>")
                        featureList.appendLine()
                    } else {
                        featureList.appendLine("# $categoryName")
                    }

                    for (module in modulesInCategory.sortedBy { it.name }) {
                        featureList.appendLine("- **${module.name}**")
                        if (module.desc.isNotEmpty()) featureList.appendLine("  - ${module.desc}")
                    }

                    if (md == true) {
                        featureList.appendLine()
                        featureList.appendLine("</details>")
                    }

                    featureList.appendLine()
                }

                mc.keyboardHandler.clipboard = featureList.toString()
            }

            "centre" {
                with(mc.player) {
                    this?.setPos(this.blockPosition().center.addVec(y = -0.5))
                }
            }

            "rotate" { yaw: Float, pitch: Float ->
                mc.player?.rotate(yaw, pitch)
            }
        }

        with(command) {
            "diagnostics" {
                runCatching {
                    mc.keyboardHandler.clipboard = DiagnosticsReport.create()
                }.onSuccess {
                    modMessage(
                        "&aDiagnostics copied to the clipboard. " +
                            "&7It lists loaded mods and enabled module names, but no player name, server address, token, individual setting value, or path.",
                    )
                }.onFailure { error ->
                    cop.CopMod.logger.warn("[Diagnostics] failed to create or copy report", error)
                    modMessage("&cCould not copy diagnostics: ${error.message ?: "unknown error"}")
                }
            }.description("Copies a privacy-safe support report to the clipboard.")

            "toggle" { moduleName: GreedyString ->
                val module = ModuleManager.getModuleByName(moduleName.string)
                module?.apply {
                    toggle()
                    toggleMessage()
                } ?: modMessage("Unknown module name: ${moduleName.string}")
            }.suggests { ModuleManager.modules.map { it.name } }.description("Toggles specified module.")

            "hud" { HudManager.openEditor() }.description("Opens Hud editor.")

            // Phase 5: Auto Croesus loot summary. Reads the JSONL log written
            // by the buy driver and prints aggregates per tier + top items.
            // Window arg: today (default) | week | all | reset.
            "loot" { windowArg: String? ->
                val arg = (windowArg ?: "today").lowercase()
                if (arg == "reset") {
                    cop.api.skyblock.croesus.CroesusLootLog.clear()
                    modMessage("&aCroesus loot log cleared.")
                    return@invoke
                }
                val window = when (arg) {
                    "today" -> cop.api.skyblock.croesus.CroesusLootLog.Window.TODAY
                    "week"  -> cop.api.skyblock.croesus.CroesusLootLog.Window.WEEK
                    "all"   -> cop.api.skyblock.croesus.CroesusLootLog.Window.ALL
                    else -> {
                        modMessage("&cUsage: /cop loot [today|week|all|reset]")
                        return@invoke
                    }
                }
                val s = cop.api.skyblock.croesus.CroesusLootLog.summarize(window)
                if (s.chestCount == 0) {
                    modMessage("&7No Croesus claims logged ${window.label}.")
                    return@invoke
                }
                val pc = cop.utils.skyblock.PriceClient
                val tierColour = mapOf(
                    "Wood" to "&7", "Gold" to "&6", "Diamond" to "&b",
                    "Emerald" to "&a", "Obsidian" to "&5", "Bedrock" to "&c",
                )
                val profitSign = if (s.totalProfit >= 0) "&a+" else "&c"
                val lines = buildList {
                    add("&6&lAuto Croesus loot &7(${window.label} • " +
                        "&f${s.chestCount}&7 chest" + (if (s.chestCount == 1) "" else "s") +
                        " across &f${s.runCount}&7 run" + (if (s.runCount == 1) "" else "s") + ")")
                    add("&7  Spent: &c${pc.formatPrice(s.totalCost)}" +
                        "  &7Earned: &a${pc.formatPrice(s.totalValue)}" +
                        "  &7Profit: $profitSign${pc.formatPrice(s.totalProfit)}" +
                        "  &7Kismets: &d${s.kismetsUsed}")
                    if (s.byTier.isNotEmpty()) {
                        add("&6By tier:")
                        for (t in s.byTier) {
                            val tc = tierColour[t.tier] ?: "&7"
                            val tp = if (t.totalProfit >= 0) "&a+" else "&c"
                            add("  ${tc}${t.tier}&7 x&f${t.count}&7  profit $tp${pc.formatPrice(t.totalProfit)}")
                        }
                    }
                    if (s.topItems.isNotEmpty()) {
                        add("&6Top items:")
                        // Lore-formatted names sometimes already include " xN"
                        // (essences, stacked books) — strip so we don't print
                        // "Wither Essence x28 x28".
                        val trailingQty = Regex("\\s+x\\d+\$")
                        for (it in s.topItems) {
                            val baseName = it.name.replace(trailingQty, "")
                            val qtyLabel = if (it.totalQty > 1) " &7x&f${it.totalQty}" else ""
                            add("  &f$baseName$qtyLabel  &a${pc.formatPrice(it.totalValue)}")
                        }
                    }
                }
                modMessage(lines.joinToString("\n"))
            }.suggests("windowArg", "today", "week", "all", "reset")
                .description("Auto Croesus loot summary. Window: today (default), week, all, reset.")

            // Phase 6: persisted skyblock-id lists that shape the auto-claim
            // driver's decisions. Same command shape for both lists; just
            // differs in which MutableList<String> we mutate.
            "alwaysbuy" { actionArg: String?, idArg: GreedyString? ->
                lootListCommand("alwaysbuy", cop.api.skyblock.croesus.CroesusLists.alwaysBuy, actionArg, idArg)
            }.suggests("actionArg", "list", "add", "remove", "clear")
                .description("Skyblock IDs the driver claims regardless of profit threshold. " +
                    "Subcommands: list (default), add <ID>, remove <ID>, clear.")

            "worthless" { actionArg: String?, idArg: GreedyString? ->
                lootListCommand("worthless", cop.api.skyblock.croesus.CroesusLists.worthless, actionArg, idArg)
            }.suggests("actionArg", "list", "add", "remove", "clear")
                .description("Skyblock IDs the price model values at 0 when computing chest profit. " +
                    "Subcommands: list (default), add <ID>, remove <ID>, clear.")
        }

        command.sub("findlobby") { area: String, criteria: String, value: String ->
            val island = Island.entries
                .firstOrNull { it.command != null && it.displayName.equals(area.replace("_", " "), true) }
                ?: return@sub modMessage("&cIncorrect area!")

            if (criteria !in setOf("day", "server", "player")) return@sub modMessage("&cInvalid criteria!")

            val intValue = if (criteria == "day") value.toIntOrNull()
                ?: return@sub modMessage("&cInvalid day number!") else null

            fun isMet(): Boolean = when (criteria) {
                "day" -> mc.level!!.day <= intValue!!
                "server" -> Location.currentServer.equals(value, true)
                "player" -> WorldUtils.players.any { it.profile.name.equals(value, true) }
                else -> false
            }

            var ticker = warpTicker(island.command!!)

            modMessage("Starting to look for $criteria $value")

            scheduleLoop {
                if (mc.player!!.isMoving) {
                    modMessage("Cancelling, you moved!")
                    it.cancel()
                    return@scheduleLoop
                }

                if (isMet() && currentArea.isArea(island)) {
                    modMessage("Found")
                    it.cancel()
                    return@scheduleLoop
                }

                if (ticker.tick()) ticker = warpTicker(island.command)
            }
        }.description("Finds lobby with specified criteria.")
        .requires("&cYou are not in skyblock!") { inSkyblock }
        .suggests("area") { Island.entries.filter { it.command != null }.map { it.displayName.replace(" ", "_") } }
        .suggests("criteria", "day", "server", "player")

        command.sub("antiafk") { delay: Int ->
            if (delay < 20) return@sub modMessage("&cThe delay is too low!")
            val headRot = mc.player!!.yHeadRot
            modMessage("Starting. Move your camera to cancel")

            var ticker = antiAfkTicker(delay)
            scheduleLoop {
                if (mc.player!!.yHeadRot != headRot) {
                    modMessage("Cancelling, you moved your camera!")
                    it.cancel()
                    return@scheduleLoop
                }

                if (ticker.tick()) ticker = antiAfkTicker(delay)
            }
        }.description("Prevents afk kick.").suggests("delay", "40")
    }

    /** Shared dispatch for the Phase 6 always-buy / worthless list commands.
     *  Both lists have identical shape: list (default), add <ID>, remove <ID>,
     *  clear. IDs are stored uppercase + trimmed for case-insensitive match. */
    private fun lootListCommand(
        label: String,
        list: MutableList<String>,
        actionArg: String?,
        idArg: GreedyString?,
    ) {
        val action = (actionArg ?: "list").lowercase()
        when (action) {
            "list" -> {
                if (list.isEmpty()) {
                    modMessage("&7Croesus $label list is empty.")
                } else {
                    modMessage("&6Croesus $label list &7(${list.size}):&r\n  &7" +
                        list.joinToString("\n  &7"))
                }
            }
            "add" -> {
                val id = idArg?.string?.trim()?.uppercase()?.replace(' ', '_')
                if (id.isNullOrEmpty()) {
                    modMessage("&cUsage: /cop $label add <SKYBLOCK_ID>")
                    return
                }
                if (id in list) modMessage("&7$id is already in the $label list.")
                else { list.add(id); modMessage("&aAdded &f$id&a to the $label list (${list.size} total).") }
            }
            "remove", "rm", "delete" -> {
                val id = idArg?.string?.trim()?.uppercase()?.replace(' ', '_')
                if (id.isNullOrEmpty()) {
                    modMessage("&cUsage: /cop $label remove <SKYBLOCK_ID>")
                    return
                }
                if (list.remove(id)) modMessage("&aRemoved &f$id&a from the $label list.")
                else modMessage("&7$id wasn't in the $label list.")
            }
            "clear" -> {
                val n = list.size
                list.clear()
                modMessage("&aCleared &f$n&a entries from the $label list.")
            }
            else -> modMessage("&cUnknown action '$action'. Use list / add <ID> / remove <ID> / clear.")
        }
    }

    fun init() {
        command.register()
        devCommand.register()

        BaseCommand("clearchat") { mc.gui.chat.clearMessages(false); Chat.chatList.clear() }.register()

        Floors.entries.forEach { floor ->
            BaseCommand(floor.name.lowercase()) {
                command("joininstance ${floor.instance()}")
            }.requires("&cYou are not in skyblock!") { inSkyblock }.register()
        }
    }

    private enum class Floors {
        F0,
        F1, F2, F3, F4, F5, F6, F7,
        M1, M2, M3, M4, M5, M6, M7;

        private val floors = listOf("one", "two", "three", "four", "five", "six", "seven")

        fun instance(): String {
            if (this == F0) return "catacombs_entrance"

            val adj = ordinal - 1

            return "${if (adj > 6) "master_" else ""}catacombs_floor_${floors[adj % 7]}"
        }
    }
}
