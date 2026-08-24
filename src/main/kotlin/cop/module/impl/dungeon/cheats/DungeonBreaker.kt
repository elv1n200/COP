package cop.module.impl.dungeon.cheats

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.*
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon.floor
import cop.api.skyblock.dungeon.Dungeon.inBoss
import cop.api.skyblock.dungeon.Dungeon.inDungeons
import cop.api.skyblock.dungeon.Dungeon.isProtectedBlock
import cop.config.configList
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.scheduleLoop
import cop.utils.StringUtils.width
import cop.utils.WorldUtils.state
import cop.utils.aabb
import cop.utils.eyePosition
import cop.utils.render.DrawContextUtils.drawText
import cop.utils.render.drawFilledBox
import cop.utils.render.drawWireFrameBox
import cop.utils.scaledHeight
import cop.utils.scaledWidth
import cop.utils.skyblock.ItemUtils.getBreakerCharges
import cop.utils.skyblock.player.interact.AuraManager
import cop.utils.skyblock.player.PlayerUtils
import cop.utils.skyblock.player.SwapManager
import cop.utils.ui.textPair

// Kyleen
object DungeonBreaker : Module(
    "Dungeon Breaker",
    area = Island.Dungeon
) {
    private val chargesHud by textHud("Charges display") {
        visibleIf { mc.player != null && inDungeons && getBreakerCharges(player.mainHandItem) > 0 }
        textPair(
            string = "Charges:",
            supplier = { mc.player?.let { getBreakerCharges(it.mainHandItem) } ?: 0 },
            labelColour = colour,
            shadow = shadow,
            font = font
        )
    }.setting()

    private val zeroPingDungeonBreaker by switch("Zero ping", desc = "Insta-mine blocks.")
    private val onlyWhenFatigue by switch("Fatigue only", desc = "Only insta-mine blocks when mining fatigue is applied.").childOf(::zeroPingDungeonBreaker)
    private val disableInInventory by switch("Disable in inventory", true, desc = "Pauses breaker automation while a screen is open.")

    private val triggerBot by switch("Look triggerbot", desc = "Mines a saved breaker block after you look at it.")
    private val triggerBotDelay by slider("Trigger delay", 0, 0, 10, 1, unit = "t").childOf(::triggerBot)

    private val autoDb by switch("Auto dungeon breaker", desc = "Automatically mines preset route when in boss. /db help")
    private val autoDbRange by slider("Auto range", 5.5, 1.0, 5.5, 0.1, unit = " blocks").childOf(::autoDb)
    private val autoDbFov by slider("Auto FOV", 360, 10, 360, 5, unit = "°").childOf(::autoDb)
    private val autoDbDelay by slider("Auto delay", 0, 0, 20, 1, unit = "t").childOf(::autoDb)
    private val zeroTickDb by switch("Zero tick").childOf(::autoDb)
    private val dbBlocks by configList<BlockPos>("dungeonbreaker_blocks.json")

    private var editMode = false
    private var lastClickedBlock: BlockPos? = null
    private var triggerTarget: BlockPos? = null
    private var triggerTicks = 0
    private var autoDbTicks = 0
    private val recentlyBroken = mutableMapOf<BlockPos, Long>()
    private val db = command.sub("db").requires("&cDungeon Breaker module is disabled!") { enabled }

    init {
        db.sub("em") {
            editMode = !editMode
            modMessage("Edit mode ${if (editMode) "&aenabled" else "&cdisabled"}&r!", id = "db em".hashCode())
        }.description("Toggles dungeon breaker edit mode.")

        db.sub("clear") {
            dbBlocks.clear()
            modMessage("&aCleared all dungeon breaker blocks.")
        }.description("Clears breaker blocks.")

        on<PacketEvent.Sent, ServerboundPlayerActionPacket> {
            if (!zeroPingDungeonBreaker) return@on
            if (editMode) return@on
            if (disableInInventory && mc.screen != null) return@on
            if (onlyWhenFatigue && !player.hasEffect(MobEffects.MINING_FATIGUE)) return@on
            if (packet.action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return@on

            val packetPos = packet.pos

            mc.execute {
                val heldItem = player.mainHandItem
                if (getBreakerCharges(heldItem) <= 0) return@execute

                if (isProtectedBlock(packetPos)) return@execute

                val clipResult = level.clip(
                    ClipContext(
                        player.eyePosition,
                        Vec3.atCenterOf(packetPos),
                        ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,
                        player
                    )
                )

                if (clipResult.type == HitResult.Type.BLOCK && clipResult.blockPos == packetPos) {
                    level.setBlock(packetPos, Blocks.AIR.defaultBlockState(), 3)
                }
            }
        }

        on<RenderEvent.World> {
            if (!autoDb) return@on
            for (pos in dbBlocks) {
                val aabb = pos.aabb
                if (pos.state.isAir) {
                    ctx.drawWireFrameBox(aabb, Colour.RED.withAlpha(125), depth = true)
                } else {
                    ctx.drawFilledBox(aabb, Colour.WHITE.withAlpha(125), depth = true)
                }
            }
        }

        on<PacketEvent.Sent> {
            if (!autoDb || !editMode || !inBoss || floor?.floorNumber != 7) return@on
            val (pos, adding) = when (packet) {
                is ServerboundUseItemOnPacket -> packet.hitResult.blockPos to false
                is ServerboundPlayerActionPacket -> {
                    if (packet.action != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) return@on
                    packet.pos to true
                }
                else -> return@on
            }

            if (lastClickedBlock == pos || isProtectedBlock(pos)) return@on
            lastClickedBlock = pos

            if (adding && !dbBlocks.contains(pos)) {
                dbBlocks.add(pos)
            } else {
                dbBlocks.remove(pos)
            }
        }

        on<TickEvent.Start> {
            lastClickedBlock = null
            if ((!autoDb && !triggerBot) || editMode || !inBoss || floor?.floorNumber != 7) return@on
            if (disableInInventory && mc.screen != null) return@on
            if (dbBlocks.isEmpty()) return@on

            if (triggerBot) tickTriggerBot()
            if (!autoDb) return@on
            if (autoDbTicks++ < autoDbDelay) return@on

            val blocks = dbBlocks.filter { pos ->
                !recentlyBroken.containsKey(pos) &&
                level.isLoaded(pos) &&
                !pos.state.isAir &&
                pos.distToCenterSqr(player.eyePosition()) <= autoDbRange * autoDbRange &&
                isInsideFov(pos, autoDbFov)
            }
            if (blocks.isEmpty()) return@on

            val breakerSlot = PlayerUtils.breakerSlot ?: return@on

            if (player.inventory.selectedSlot != breakerSlot) {
                if (!SwapManager.swapToSlot(breakerSlot).success) return@on
                return@on
            }

            val initialCharges = getBreakerCharges(player.inventory.getItem(breakerSlot))
            if (initialCharges == 0) return@on

            blocks.forEachIndexed { i, pos ->
                if (i >= initialCharges) return@on
                AuraManager.breakBlock(pos, immediate = true)
                recentlyBroken[pos] = System.currentTimeMillis()
                autoDbTicks = 0
                if (!zeroTickDb) return@on
            }
        }

        on<RenderEvent.Overlay> {
            if (!editMode) return@on
            val t = "DB Edit Mode"
            val x = (scaledWidth - t.width()) / 2f
            val y = (scaledHeight + 40) / 2f
            ctx.drawText(t, x, y)
        }

        scheduleLoop(10) {
            if (enabled) clearCooldownCache()
        }
    }

    private fun clearCooldownCache() {
        val now = System.currentTimeMillis()
        recentlyBroken.entries.removeIf { (pos, time) -> now - time > 10_500 || !pos.state.isAir }
    }

    private fun tickTriggerBot() {
        val hit = mc.hitResult as? BlockHitResult
        val pos = hit?.takeIf { it.type == HitResult.Type.BLOCK }?.blockPos
            ?.takeIf { it in dbBlocks && isMineable(it, 5.5) }

        if (pos == null) {
            triggerTarget = null
            triggerTicks = 0
            return
        }

        if (triggerTarget != pos) {
            triggerTarget = pos
            triggerTicks = 0
        }
        if (triggerTicks++ < triggerBotDelay) return

        val breakerSlot = PlayerUtils.breakerSlot ?: return
        if (player.inventory.selectedSlot != breakerSlot) {
            SwapManager.swapToSlot(breakerSlot)
            return
        }
        if (getBreakerCharges(player.inventory.getItem(breakerSlot)) <= 0) return

        AuraManager.breakBlock(pos, immediate = true)
        recentlyBroken[pos] = System.currentTimeMillis()
        triggerTarget = null
        triggerTicks = 0
    }

    private fun isMineable(pos: BlockPos, range: Double): Boolean =
        !recentlyBroken.containsKey(pos) && level.isLoaded(pos) && !pos.state.isAir &&
            pos.distToCenterSqr(player.eyePosition()) <= range * range

    private fun isInsideFov(pos: BlockPos, fov: Int): Boolean {
        if (fov >= 360) return true
        val direction = Vec3.atCenterOf(pos).subtract(player.eyePosition()).normalize()
        val threshold = kotlin.math.cos(Math.toRadians(fov / 2.0))
        return player.lookAngle.normalize().dot(direction) >= threshold
    }
}
