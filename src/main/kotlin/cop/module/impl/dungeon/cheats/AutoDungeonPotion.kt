package cop.module.impl.dungeon.cheats

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.world.item.ItemStack
import cop.CopMod.scope
import cop.api.events.ChatEvent
import cop.api.events.DungeonEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Floor
import cop.api.skyblock.invoke
import cop.module.Category
import cop.module.Module
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.scheduleTask
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.ItemUtils.extraAttributes
import cop.utils.skyblock.player.AutomationCoordinator
import cop.utils.skyblock.player.AutomationCoordinator.Channel
import cop.utils.skyblock.player.ContainerUtils
import cop.utils.skyblock.player.MovementUtils.stop

/** Retrieves a real Dungeon Potion item from the Potion Bag at run start. */
object AutoDungeonPotion : Module(
    "Auto Dungeon Potion",
    area = Island.Dungeon(inClear = true),
    desc = "Takes a suitable Dungeon Potion from your Potion Bag when a run starts.",
    explicitCategory = Category.DUNGEON,
    explicitSubCategory = "qol",
) {
    private val floorMode by selector(
        "Floors",
        FloorMode.M7,
        desc = "Controls on which floors a potion is taken."
    )
    private val minimumTier by selector(
        "Minimum tier",
        PotionTier.VII,
        desc = "Lowest acceptable Dungeon Potion tier."
    )
    private val triggerDelay by slider(
        "Start delay",
        5,
        0,
        40,
        1,
        unit = "t",
        desc = "Delay after Mort's run-start message."
    )
    private val stopMoving by switch(
        "Prevent moving",
        true,
        desc = "Stops movement while the hidden Potion Bag action is running."
    )
    private val statusMessages by switch("Status messages", true, desc = "Reports potion-grab results in chat.")

    private var grabJob: Job? = null
    private var worldEpoch = 0
    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }

    init {
        on<WorldEvent.Change> {
            worldEpoch++
            reset()
        }

        on<TickEvent.Start> {
            if (stopMoving && grabJob?.isActive == true) mc.player?.stop()
        }

        on<DungeonEvent.Start> {
            val epoch = worldEpoch
            scheduleTask(triggerDelay) {
                if (!handlesCurrentRun() || epoch != worldEpoch || grabJob?.isActive == true) {
                    return@scheduleTask
                }
                startGrab(epoch)
            }
        }

        on<ChatEvent.PacketClient> {
            if (message.noControlCodes == COOKIE_REQUIRED_LINE && grabJob?.isActive == true) {
                status("&cPotion grab cancelled: Cookie Buff is required.")
                reset()
            }
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun startGrab(epoch: Int) {
        val job = scope.launch(clientDispatcher, start = CoroutineStart.LAZY) {
            if (!AutomationCoordinator.acquire(OWNER, 8_000L, Channel.INVENTORY, Channel.MOVEMENT)) {
                status("&cPotion grab skipped: another inventory automation is active.")
                return@launch
            }

            var ownedSession: ContainerUtils.ContainerSession? = null
            try {
                if (!handlesCurrentRun() || epoch != worldEpoch) return@launch
                if (mc.screen != null) {
                    status("&cPotion grab skipped: another menu is open.")
                    return@launch
                }
                val inventory = mc.player?.inventory ?: return@launch
                val inventoryItems = (0..35).map(inventory::getItem)
                val existingTier = inventoryItems.mapNotNull { it.dungeonPotionTier() }.maxByOrNull { it.level }
                if (existingTier != null && existingTier.level >= minimumTier.selected.level) {
                    status("&eAlready carrying a Dungeon $existingTier Potion.")
                    return@launch
                }
                if (inventoryItems.none(ItemStack::isEmpty)) {
                    status("&cCouldn't get a Dungeon Potion: your inventory is full.")
                    return@launch
                }

                val snapshot = ContainerUtils.getContainerSnapshot(
                    command = "potionbag",
                    containerName = "Potion Bag",
                    slots = 54,
                    timeout = 40
                ) ?: return@launch
                ownedSession = snapshot.session
                val items = snapshot.items
                if (!handlesCurrentRun() || epoch != worldEpoch) return@launch

                val target = items.withIndex()
                    .mapNotNull { (index, stack) ->
                        val tier = stack?.dungeonPotionTier() ?: return@mapNotNull null
                        if (tier.level < minimumTier.selected.level) null else PotionCandidate(index, tier)
                    }
                    .maxByOrNull { it.tier.level }

                if (target == null) {
                    status("&cNo Dungeon Potion tier ${minimumTier.selected} or higher was found in your Potion Bag.")
                    return@launch
                }

                if (!ContainerUtils.clickAwait(snapshot.session, target.slot)) {
                    status("&cCouldn't click the Dungeon Potion slot.")
                    return@launch
                }

                delay(100L)
                status("&aTook a Dungeon ${target.tier} Potion from your Potion Bag.")
            } catch (_: CancellationException) {
                throw CancellationException("Auto Dungeon Potion cancelled")
            } finally {
                ownedSession?.let { ContainerUtils.closeContainer(it) }
                AutomationCoordinator.release(OWNER)
            }
        }

        grabJob = job
        job.invokeOnCompletion { mc.execute { if (grabJob === job) grabJob = null } }
        job.start()
    }

    private fun reset() {
        grabJob?.cancel()
        grabJob = null
        AutomationCoordinator.release(OWNER)
    }

    fun handlesCurrentRun(): Boolean =
        enabled && inEnvironment() && !Dungeon.isDead && matchesFloor(Dungeon.floor)

    private fun matchesFloor(floor: Floor?): Boolean = when (floorMode.selected) {
        FloorMode.ALL -> floor != null
        FloorMode.MASTER_MODE -> floor?.isMM == true
        FloorMode.F7_AND_M7 -> floor == Floor.F7 || floor == Floor.M7
        FloorMode.M7 -> floor == Floor.M7
    }

    private fun ItemStack.dungeonPotionTier(): PotionTier? {
        if (isEmpty) return null
        val attributes = extraAttributes ?: return null
        if (attributes.getString("id").orElse(null) != "POTION") return null
        if (!attributes.getString("potion_name").orElse(null).equals("Dungeon", ignoreCase = true)) return null
        val level = attributes.getInt("potion_level").orElse(null) ?: return null
        return PotionTier.entries.firstOrNull { it.level == level }
    }

    private fun status(message: String) {
        if (statusMessages) modMessage(message)
    }

    private data class PotionCandidate(val slot: Int, val tier: PotionTier)

    private enum class PotionTier(val level: Int) {
        I(1), II(2), III(3), IV(4), V(5), VI(6), VII(7)
    }

    private enum class FloorMode(private val displayName: String) {
        ALL("All floors"),
        MASTER_MODE("Master Mode"),
        F7_AND_M7("F7 + M7"),
        M7("M7 only");

        override fun toString() = displayName
    }

    private const val OWNER = "AutoDungeonPotion"
    private const val COOKIE_REQUIRED_LINE = "You need the Cookie Buff active to use this feature!"
}
