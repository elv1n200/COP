package cop.module.impl.misc

import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cop.CopMod.scope
import cop.api.events.MouseEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.config.Config
import cop.module.Module
import cop.module.settings.UIComponent.Companion.childOf
import cop.module.settings.impl.ListSetting
import cop.utils.ChatUtils.modMessage
import cop.utils.StringUtils.formattedString
import cop.utils.skyblock.ItemUtils.skyblockUuid
import cop.utils.skyblock.ItemUtils.skyblockId
import cop.utils.skyblock.player.PlayerUtils.isLookingAtBreakable
import cop.utils.skyblock.player.PlayerUtils.leftClick
import cop.utils.skyblock.player.PlayerUtils.rightClick
import kotlin.random.Random
import net.minecraft.world.phys.BlockHitResult

// https://github.com/Noamm9/CatgirlAddons/blob/main/src/main/kotlin/catgirlroutes/module/impl/misc/AutoClicker.kt
object AutoClicker: Module(
    "Auto Clicker",
    desc = "A simple auto clicker for both left and right click. Activates when the corresponding key is being held down."
) {
    private val breakBlocks by switch("Break blocks", desc = "Allows the player to break blocks.")
//    private val clickInGui by BooleanSetting("Click while in inventory", desc = "Continues to auto click while the player is in inventory.")
    private val favouriteItems by switch("Favourite items only")
    private val favLeft by ListSetting("FAVOURITE_ITEMS_LEFT", mutableListOf<String>())
    private val favRight by ListSetting("FAVOURITE_ITEMS_RIGHT", mutableListOf<String>())

    private val leftClick by switch("Left Click", desc = "Toggles the auto clicker for left click.")
    private val leftCps by rangeSlider("Left CPS", 10 to 12, 1, 20).childOf(::leftClick)

    private val rightClick by switch("Right Click", desc = "Toggles the auto clicker for right click.")
    private val rightCps by rangeSlider("Right CPS", 10 to 12, 1, 20).childOf(::rightClick)

    private val terminatorMode by switch(
        "Terminator Salvation", desc = "Uses Salvation with left clicks while you hold right click on a Terminator.",
    )
    private val terminatorCps by slider("Terminator CPS", 7.0, 5.0, 10.0, 0.5).childOf(::terminatorMode)
    private val protectDungeonSecrets by switch(
        "Protect dungeon secrets", true,
        desc = "Does not fire Salvation at protected secret blocks outside the boss.",
    ).childOf(::terminatorMode)

    private var leftJob: Job? = null
    private var rightJob: Job? = null
    private var lastHeldSlot = -1
    private var isMining = false
    private var nextTerminatorClick = 0L
    private var terminatorDriftCps = 7.0
    private var lastTerminatorDriftAt = 0L
    private val terminatorRandom = java.util.Random()
    private val clientDispatcher by lazy { mc.asCoroutineDispatcher() }

    private fun shouldClick(isLeft: Boolean): Boolean {
        if (mc.screen != null) return false
        val favList = if (isLeft) favLeft else favRight
        return !favouriteItems || player.mainHandItem.skyblockUuid in favList
    }

    private val lookingAtBreakable get() = breakBlocks && player.isLookingAtBreakable

    init {
        val ac = command.sub("ac").description("Auto Clicker module settings.")

        ac.sub("add") { button: String ->
            stupid(button) { list ->
                val uuid = player.mainHandItem.skyblockUuid ?: return@stupid modMessage("&cYou are not holding a skyblock item!")
                if (list.contains(uuid)) return@stupid modMessage("&cThis item is already in the $button list!")

                list.add(uuid)
                modMessage("&aAdded ${player.mainHandItem.displayName.formattedString} &ato $button list!")
            }
        }.suggests("button", "left", "right")
        ac.sub("remove") { button: String ->
            stupid(button) { list ->
                val uuid = player.mainHandItem.skyblockUuid ?: return@stupid modMessage("&cYou are not holding a skyblock item!")
                if (!list.remove(uuid)) return@stupid modMessage("&cThis item is not in the $button list!")

                modMessage("&aRemoved ${player.mainHandItem.displayName.formattedString} &afrom $button list!")
            }
        }.suggests("button", "left", "right")
        ac.sub("clear") { button: String ->
            stupid(button) { list ->
                list.clear()
                modMessage("&aCleared the $button list!")
            }
        }.suggests("button", "left", "right")

        on<MouseEvent.Click> {
            if (button !in 0..1) return@on

            val isLeft = button == 0
            val enabled = if (isLeft) leftClick else rightClick

            if (state && enabled && shouldClick(isLeft)) {
                cancel()
                startClicking(isLeft)
            } else {
                stopClicking(isLeft)
            }
        }

        on<TickEvent.End> {
            val currentSlot = player.inventory.selectedSlot
            if (currentSlot != lastHeldSlot || mc.screen != null) {
                reset()
                lastHeldSlot = currentSlot
            }

            if (leftJob != null && lookingAtBreakable) {
                if (!isMining) {
                    mc.options.keyAttack.isDown = true
                    isMining = true
                }
            } else if (isMining) {
                mc.options.keyAttack.isDown = false
                isMining = false
            }
        }

        on<WorldEvent.Change> {
            reset()
        }
    }

    private fun startClicking(isLeft: Boolean) {
        if (isLeft) {
            if (leftJob != null) return
            leftJob = scope.launch(clientDispatcher) { click(true) }
        } else {
            if (rightJob != null) return
            rightJob = scope.launch(clientDispatcher) { click(false) }
        }

        on<TickEvent.Start> {
            if (!terminatorMode || mc.screen != null || !mc.options.keyUse.isDown) return@on
            if (player.mainHandItem.skyblockId != "TERMINATOR" || player.isUsingItem) return@on

            if (protectDungeonSecrets && Dungeon.inDungeons && !Dungeon.inBoss) {
                val hit = mc.hitResult as? BlockHitResult
                if (hit != null && Dungeon.isProtectedBlock(hit.blockPos)) return@on
            }

            val now = System.currentTimeMillis()
            if (now < nextTerminatorClick) return@on
            if (now - lastTerminatorDriftAt >= 1_000L) {
                terminatorDriftCps = (terminatorCps + terminatorRandom.nextGaussian()).coerceIn(5.0, 10.0)
                lastTerminatorDriftAt = now
            }

            player.leftClick()
            var delay = (1_000.0 / terminatorDriftCps).toLong()
            val noise = terminatorRandom.nextGaussian()
            delay += if (noise < 0) (noise * 10.0).toLong() else (noise * 25.0).toLong()
            val roll = terminatorRandom.nextDouble()
            if (roll < 0.01) delay += terminatorRandom.nextInt(100, 250)
            else if (roll < 0.03) delay = terminatorRandom.nextInt(5, 15).toLong()
            nextTerminatorClick = now + delay.coerceAtLeast(5L)
        }
    }

    override fun onDisable() {
        reset()
        super.onDisable()
    }

    private fun stopClicking(isLeft: Boolean) {
        if (isLeft) {
            leftJob?.cancel()
            leftJob = null
            if (isMining) {
                mc.options.keyAttack.isDown = false
                isMining = false
            }
        } else {
            rightJob?.cancel()
            rightJob = null
        }
    }

    private suspend fun click(isLeft: Boolean) {
        val range = if (isLeft) leftCps else rightCps

        while (true) {
            if (shouldClick(isLeft)) {
                if (isLeft) {
                    if (!lookingAtBreakable) player.leftClick()
                } else {
                    player.rightClick()
                }
            }

            val min = 1000L / range.second
            val max = 1000L / range.first

            delay(if (min == max) min else Random.nextLong(min, max))
        }
    }

    private fun stupid(button: String, block: (MutableList<String>) -> Unit) {
        val list = when (button.lowercase()) {
            "left" -> favLeft
            "right" -> favRight
            else -> return modMessage("&cInvalid button! Use \"left\" or \"right\".")
        }
        block(list)
        Config.save()
    }

    private fun reset() {
        stopClicking(true)
        stopClicking(false)
        lastHeldSlot = -1
        nextTerminatorClick = 0L
        lastTerminatorDriftAt = 0L
    }
}
