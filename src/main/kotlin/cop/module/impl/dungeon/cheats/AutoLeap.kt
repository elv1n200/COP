package cop.module.impl.dungeon.cheats

import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.AABB
import cop.api.events.ChatEvent
import cop.api.events.DungeonEvent
import cop.api.events.MouseEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Dungeon.allTeammatesNoSelf
import cop.api.skyblock.dungeon.DungeonClass
import cop.api.skyblock.dungeon.Floor
import cop.api.skyblock.dungeon.M7Phases
import cop.api.skyblock.dungeon.P3Section
import cop.module.Module
import cop.module.settings.Setting.Companion.json
import cop.module.settings.UIComponent.Companion.childOf
import cop.utils.StringUtils.noControlCodes
import cop.utils.skyblock.player.LeapManager

// Kyleen (maybe)
object AutoLeap : Module( // todo clean up
    "Auto Leap",
    desc = "Automatically leaps to predefined targets.",
    area = Island.Dungeon
) {
    private val fastLeap by switch("Fast leap", desc = "Leaps to a set player on infinileap left click.")
    private val fastDelay by slider("Delay", 250L, 100L, 500L, 50L).childOf(::fastLeap) // to not pull bko
    private val autoLeap by switch("Auto leap", desc = "Automatically leaps when a section is finished.")
    private val whenBlown by switch("Only when gate blown", desc = "Only leaps when gate is blown").childOf(::autoLeap)
    private val leapMode by selector("Leap mode", "Name", listOf("Name", "Class"), "Leap mode for the module.").open()

    private val clearName by textInput("Clear leap", "Clear", length = 16).childOf(::leapMode) { it.index == 0 }.suggests { allTeammatesNoSelf }
    private val s1Name by textInput("S1 leap", "S1", length = 16).childOf(::leapMode) { it.index == 0 }.suggests { allTeammatesNoSelf }
    private val s2Name by textInput("S2 leap", "S2", length = 16).childOf(::leapMode) { it.index == 0 }.suggests { allTeammatesNoSelf }
    private val s3Name by textInput("S3 leap", "S3", length = 16).childOf(::leapMode) { it.index == 0 }.suggests { allTeammatesNoSelf }
    private val s4Name by textInput("S4 leap", "S4", length = 16).childOf(::leapMode) { it.index == 0 }.suggests { allTeammatesNoSelf }

    private val clearClass by selector("Clear leap", DungeonClass.Unknown).json("Clear leap class").childOf(::leapMode) { it.index == 1 }
    private val s1Class by selector("S1 leap", DungeonClass.Healer).json("S1 leap class").childOf(::leapMode) { it.index == 1 }
    private val s2Class by selector("S2 leap", DungeonClass.Archer).json("S2 leap class").childOf(::leapMode) { it.index == 1 }
    private val s3Class by selector("S3 leap", DungeonClass.Mage).json("S3 leap class").childOf(::leapMode) { it.index == 1 }
    private val s4Class by selector("S4 leap", DungeonClass.Mage).json("S4 leap class").childOf(::leapMode) { it.index == 1 }

    private val extendedLeaps by text("Extended F7 / M7 leaps")

    private val doorOpenerLeap by switch("Door opener leap", desc = "Leap to the teammate who opens a Wither or Blood door.").childOf(::extendedLeaps)
    private val doorOpenerAuto by switch("Auto", true).json("Door opener auto").childOf(::doorOpenerLeap)

    private val p1Leap by switch("P1 leap", desc = "Leap after the second Energy Laser charge.").childOf(::extendedLeaps)
    private val p1Auto by switch("Auto", true).json("P1 leap auto").childOf(::p1Leap)
    private val p1Name by textInput("P1 target", "P1", length = 16).childOf(::p1Leap) { p1Leap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val p1Class by selector("P1 target", DungeonClass.Unknown).json("P1 target class").childOf(::p1Leap) { p1Leap && leapMode.index == 1 }

    private val predevLeap by switch("Predev leap", desc = "Leap when Storm starts the predev timing line.").childOf(::extendedLeaps)
    private val predevAuto by switch("Auto", true).json("Predev leap auto").childOf(::predevLeap)
    private val predevName by textInput("Predev target", "Predev", length = 16).childOf(::predevLeap) { predevLeap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val predevClass by selector("Predev target", DungeonClass.Unknown).json("Predev target class").childOf(::predevLeap) { predevLeap && leapMode.index == 1 }

    private val greenLeap by switch("Green pad leap").childOf(::extendedLeaps)
    private val greenAuto by switch("Auto", true).json("Green leap auto").childOf(::greenLeap)
    private val greenName by textInput("Green target", "Green", length = 16).childOf(::greenLeap) { greenLeap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val greenClass by selector("Green target", DungeonClass.Unknown).json("Green target class").childOf(::greenLeap) { greenLeap && leapMode.index == 1 }

    private val yellowLeap by switch("Yellow pad leap").childOf(::extendedLeaps)
    private val yellowAuto by switch("Auto", true).json("Yellow leap auto").childOf(::yellowLeap)
    private val yellowName by textInput("Yellow target", "Yellow", length = 16).childOf(::yellowLeap) { yellowLeap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val yellowClass by selector("Yellow target", DungeonClass.Unknown).json("Yellow target class").childOf(::yellowLeap) { yellowLeap && leapMode.index == 1 }

    private val purpleLeap by switch("Purple pad leap").childOf(::extendedLeaps)
    private val purpleAuto by switch("Auto", true).json("Purple leap auto").childOf(::purpleLeap)
    private val purpleName by textInput("Purple target", "Purple", length = 16).childOf(::purpleLeap) { purpleLeap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val purpleClass by selector("Purple target", DungeonClass.Unknown).json("Purple target class").childOf(::purpleLeap) { purpleLeap && leapMode.index == 1 }

    private val i4Leap by switch("I4 / Pre4 leap").childOf(::extendedLeaps)
    private val i4Auto by switch("Auto", true).json("I4 leap auto").childOf(::i4Leap)
    private val i4Name by textInput("I4 target", "Pre4", length = 16).childOf(::i4Leap) { i4Leap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val i4Class by selector("I4 target", DungeonClass.Unknown).json("I4 target class").childOf(::i4Leap) { i4Leap && leapMode.index == 1 }

    private val middleLeap by switch("Middle leap").childOf(::extendedLeaps)
    private val middleAuto by switch("Auto", true).json("Middle leap auto").childOf(::middleLeap)
    private val middleName by textInput("Middle target", "Middle", length = 16).childOf(::middleLeap) { middleLeap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val middleClass by selector("Middle target", DungeonClass.Unknown).json("Middle target class").childOf(::middleLeap) { middleLeap && leapMode.index == 1 }

    private val p5Leap by switch("P5 leap").childOf(::extendedLeaps)
    private val p5Auto by switch("Auto", true).json("P5 leap auto").childOf(::p5Leap)
    private val p5Name by textInput("P5 target", "P5", length = 16).childOf(::p5Leap) { p5Leap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val p5Class by selector("P5 target", DungeonClass.Unknown).json("P5 target class").childOf(::p5Leap) { p5Leap && leapMode.index == 1 }

    private val relicLeap by switch("Relic leap").childOf(::extendedLeaps)
    private val relicAuto by switch("Auto", true).json("Relic leap auto").childOf(::relicLeap)
    private val relicName by textInput("Relic target", "Relic", length = 16).childOf(::relicLeap) { relicLeap && leapMode.index == 0 }.suggests { allTeammatesNoSelf }
    private val relicClass by selector("Relic target", DungeonClass.Unknown).json("Relic target class").childOf(::relicLeap) { relicLeap && leapMode.index == 1 }

    private var lastClick = 0L
    private var laserCount = 0
    private var crushCount = 0
    private var necronArghCount = 0
    private var relicHandled = false

    private val doorOpenRegex = Regex("^(?:\\[\\w+] )?(\\w+) opened a (?:WITHER|Blood) door!")
    private val deviceDoneRegex = Regex("^(\\w+) completed a device! \\(.*\\)$")
    private val greenPad = AABB(24.0, 170.0, 4.0, 41.0, 172.0, 21.0)
    private val yellowPad = AABB(24.0, 170.0, 86.0, 41.0, 172.0, 103.0)
    private val purplePad = AABB(95.0, 164.0, 86.0, 123.0, 172.0, 103.0)
    private val middle = AABB(47.0, 64.0, 69.0, 61.0, 75.0, 83.0)
    private val pre4 = AABB(62.0, 127.0, 34.0, 65.0, 131.0, 37.0)

    private val doNotLeapLocations = listOf(
        Vec3(108.5, 120.0, 94.5) to 1.5, // at ss
        Vec3(58.5, 109.0, 131.5) to 1.5, // at ee2
        Vec3(60.5, 132.0, 140.5) to 1.5, // at ee2 high / levers dev
        Vec3(69.5, 109.0, 122.5) to 1.0, // ee2 safe spot 1
        Vec3(48.5, 109.0, 122.5) to 1.0, // ee2 safe spot 2
        Vec3(2.5, 109.0, 104.5) to 1.5,  // at ee3
        Vec3(18.5, 121.0, 99.5) to 3.0,  // ee3 safe spot
        Vec3(1.5, 120.0, 77.5) to 3.0,   // arrows dev
        Vec3(58.5, 123.0, 122.5) to 0.3, // entering core
        Vec3(54.5, 115.0, 51.5) to 1.5   // at core
    )

    init {
        on<DungeonEvent.SectionComplete> {
            if (!autoLeap || !Dungeon.inP3) return@on
            if (whenBlown) return@on
            handleLeap(completedSection = Dungeon.p3Section)
        }

        on<DungeonEvent.SectionComplete.Full> {
            if (!autoLeap || !Dungeon.inP3) return@on
            if (!whenBlown) return@on
            handleLeap(completedSection = Dungeon.p3Section)
        }

        on<ChatEvent.PacketClient> {
            val plain = message.noControlCodes

            doorOpenRegex.find(plain)?.groupValues?.getOrNull(1)?.let { opener ->
                if (doorOpenerLeap && doorOpenerAuto && opener != player.name.string && !Dungeon.inBoss) {
                    LeapManager.leap(opener)
                }
            }

            when (plain) {
                "The Energy Laser is charging up!" -> if (++laserCount == 2 && p1Leap && p1Auto) leapConfigured(p1Name, p1Class.selected)
                "[BOSS] Storm: I'd be happy to show you what that's like!" -> if (predevLeap && predevAuto) leapConfigured(predevName, predevClass.selected)
                "[BOSS] Storm: Oof", "[BOSS] Storm: Ouch, that hurt!" -> {
                    crushCount++
                    if (crushCount == 1 && greenLeap && greenAuto && greenPad.contains(player.position())) leapConfigured(greenName, greenClass.selected)
                    if (crushCount == 2 && yellowLeap && yellowAuto && yellowPad.contains(player.position())) leapConfigured(yellowName, yellowClass.selected)
                }
                "⚠ Storm is enraged! ⚠" -> if (purpleLeap && purpleAuto && purplePad.contains(player.position())) leapConfigured(purpleName, purpleClass.selected)
                "[BOSS] Necron: That's a very impressive trick. I guess I'll have to handle this myself." -> {
                    if (middleLeap && middleAuto && !middle.contains(player.position())) leapConfigured(middleName, middleClass.selected)
                }
                "[BOSS] Necron: ARGH!" -> if (++necronArghCount == 2 && p5Leap && p5Auto) leapConfigured(p5Name, p5Class.selected)
            }

            deviceDoneRegex.matchEntire(plain)?.groupValues?.getOrNull(1)?.let { who ->
                if (i4Leap && i4Auto && !AutoI4.handlesAutomaticLeap() &&
                    who == player.name.string && pre4.contains(player.position())) {
                    leapConfigured(i4Name, i4Class.selected)
                }
            }

            if (!autoLeap) return@on
            if (plain == "[BOSS] Storm: I should have known that I stood no chance.") {
                handleLeap(forceS1 = true)
            }
            if (plain == "The Core entrance is opening!") {
                handleLeap(completedSection = P3Section.S4)
            }
        }

        on<TickEvent.End> {
            if (!relicLeap || !relicAuto || relicHandled || Dungeon.floor != Floor.M7 ||
                Dungeon.getF7Phase() != M7Phases.P5) return@on
            val relic = player.inventory.getItem(8)
            if (!relic.hoverName.string.contains("Relic", ignoreCase = true)) return@on
            if (leapConfigured(relicName, relicClass.selected)) relicHandled = true
        }

        on<WorldEvent.Change> { resetExtendedState() }

        on<MouseEvent.Click> {
            if (!fastLeap || button != 0 || !state) return@on
            if (Dungeon.getP3Section() == P3Section.Unknown && !Dungeon.inClear) return@on
//            if (player.mainHandItem.skyblockId != "INFINITE_SPIRIT_LEAP") return@on
            if (!player.mainHandItem.displayName.string.contains("InfiniLeap", true)) return@on

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastClick < fastDelay) return@on
            handleLeap(autoLeap = false)
            lastClick = currentTime
        }
    }

    override fun onDisable() {
        resetExtendedState()
        super.onDisable()
    }

    private fun leapConfigured(name: String, clazz: DungeonClass): Boolean = when (leapMode.selected) {
        "Name" -> name.isNotBlank() && LeapManager.leap(name)
        "Class" -> clazz != DungeonClass.Unknown && LeapManager.leap(clazz)
        else -> false
    }

    private fun resetExtendedState() {
        laserCount = 0
        crushCount = 0
        necronArghCount = 0
        relicHandled = false
    }

    private fun handleLeap(completedSection: P3Section? = null, forceS1: Boolean = false, autoLeap: Boolean = true) {
        if (autoLeap) {
            if (Dungeon.getP3Section() == P3Section.Unknown) return
            for ((pos, radius) in doNotLeapLocations) {
                if (player.distanceToSqr(pos) <= radius * radius) {
                    return
                }
            }
        }

        val targetSection = if (forceS1) {
            P3Section.S1
        } else if (completedSection != null && completedSection != P3Section.Unknown) {
            when (completedSection) {
                P3Section.S1 -> P3Section.S2
                P3Section.S2 -> P3Section.S3
                P3Section.S3 -> P3Section.S4
                P3Section.S4 -> P3Section.S4
            }
        } else {
            Dungeon.getP3Section()
        }

        val (name, clazz) = if (Dungeon.inClear) {
            clearName to clearClass.selected
        } else {
            when (targetSection) {
                P3Section.S1 -> s1Name to s1Class.selected
                P3Section.S2 -> s2Name to s2Class.selected
                P3Section.S3 -> s3Name to s3Class.selected
                P3Section.S4 -> s4Name to s4Class.selected
                else -> return
            }
        }

        when (leapMode.selected) {
            "Name" -> LeapManager.leap(name)
            "Class" -> LeapManager.leap(clazz)
        }
    }
}
