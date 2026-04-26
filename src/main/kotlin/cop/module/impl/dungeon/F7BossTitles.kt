package cop.module.impl.dungeon

import cop.api.events.ChatEvent
import cop.api.skyblock.Island
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.invoke
import cop.module.Module
import cop.utils.skyblock.player.PlayerUtils

/**
 * Adapted concept from NoammAddons F7Titles (com.github.noamm9.features.impl.floor7.F7Titles).
 * Reimplemented against Quoi's Module + ChatEvent API. Shows on-screen callouts for key
 * F7 boss moments (Maxor stunned, Storm crushed, etc).
 */
object F7BossTitles : Module(
    "F7 Boss Titles",
    area = Island.Dungeon(7, inBoss = true),
    desc = "Big on-screen callouts for Maxor/Storm/Goldor/Necron phase events."
) {
    private val maxorTitles by switch("Maxor titles", true,
        desc = "Shows a title when Maxor is stunned/killed.")
    private val stormTitles by switch("Storm titles", true,
        desc = "Shows a title when Storm is crushed/dead.")
    private val bossDeath by switch("Boss death titles", true,
        desc = "Shows a title when Goldor/Necron die.")

    init {
        on<ChatEvent.Receive> {
            val text = message
            when {
                maxorTitles && (text == "[BOSS] Maxor: YOU TRICKED ME!"
                        || text == "[BOSS] Maxor: THAT BEAM! IT HURTS! IT HURTS!!") ->
                    title("§dMaxor Stunned!")

                stormTitles && (text == "[BOSS] Storm: Oof" || text == "[BOSS] Storm: Ouch, that hurt!") ->
                    title("§bStorm Crushed!")

                stormTitles && text == "[BOSS] Storm: I should have known that I stood no chance." ->
                    title("§bStorm Dead!")

                bossDeath && text == "[BOSS] Goldor: Who dares trespass into my domain?" ->
                    title("§eGoldor started!")

                bossDeath && text == "[BOSS] Necron: ARGH!" ->
                    title("§cNecron Phase Started!")
            }
        }
    }

    private fun title(subtitle: String) {
        if (!Dungeon.isFloor(7) || !Dungeon.inBoss) return
        PlayerUtils.setTitle("", subtitle, playSound = true, stayAlive = 35, fadeOut = 10)
    }
}
