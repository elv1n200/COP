package cop.module.impl.render

import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.components.ImageButton
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import cop.api.events.GuiEvent
import cop.api.events.PacketEvent
import cop.api.events.RenderEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.module.Module
import cop.utils.skyblock.ItemUtils.texture
import cop.utils.textures

object RenderOptimiser : Module(
    "Render Optimiser",
    desc = "Various render optimisation features."
) {
    @JvmStatic val disableTextShadow by switch("Disable text shadow", desc = "Disables text shadows in hud elements.")
    @JvmStatic val containerTextShadow by switch("Container text shadow", desc = "Renders text in containers with shadow.")
    @JvmStatic val disableFog by switch("Disable fog", desc = "Disables fog rendering.")

    private val hideFallingBlocks by switch("Hide falling blocks", desc = "Disables falling blocks rendering.")
    private val hideLightning by switch("Hide lightning", desc = "Disables lightning rendering.")
    private val hideWeaver by switch("Hide soul weaver", desc = "Disables soul weaver skulls rendering.")
    private val hideFairy by switch("Hide healer fairy", desc = "Disables healer fairy rendering.")
    private val hideRecipeBook by switch("Hide recipe book", desc = "Disables recipe book rendering.")
    private val hideBlindness by switch("Hide blindness", desc = "Disabled blindness effect rendering.")
    @JvmStatic val hideFire by switch("Hide fire overlay", desc = "Disables fire overlay rendering.")

    @JvmStatic val fullBright by switch("Full bright", desc = "Makes dark places bright.")

    private const val HEALER_FAIRY_TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMTcxOTQ2MzA5MTA0NywKICAicHJvZmlsZUlkIiA6ICIyNjRkYzBlYjVlZGI0ZmI3OTgxNWIyZGY1NGY0OTgyNCIsCiAgInByb2ZpbGVOYW1lIiA6ICJxdWludHVwbGV0IiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzJlZWRjZmZjNmExMWEzODM0YTI4ODQ5Y2MzMTZhZjdhMjc1MmEzNzZkNTM2Y2Y4NDAzOWNmNzkxMDhiMTY3YWUiCiAgICB9CiAgfQp9"
    private const val SOUL_WEAVER_TEXTURE = "eyJ0aW1lc3RhbXAiOjE1NTk1ODAzNjI1NTMsInByb2ZpbGVJZCI6ImU3NmYwZDlhZjc4MjQyYzM5NDY2ZDY3MjE3MzBmNDUzIiwicHJvZmlsZU5hbWUiOiJLbGxscmFoIiwic2lnbmF0dXJlUmVxdWlyZWQiOnRydWUsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8yZjI0ZWQ2ODc1MzA0ZmE0YTFmMGM3ODViMmNiNmE2YTcyNTYzZTlmM2UyNGVhNTVlMTgxNzg0NTIxMTlhYTY2In19fQ=="


    init {
        on<PacketEvent.Received, ClientboundAddEntityPacket> {
            if (hideFallingBlocks && packet.type == EntityType.FALLING_BLOCK ||
                hideLightning && packet.type == EntityType.LIGHTNING_BOLT) cancel()
        }

        on<PacketEvent.Received, ClientboundUpdateMobEffectPacket> {
            val player = mc.player ?: return@on
            if (hideBlindness && packet.entityId == player.id && packet.effect == MobEffects.BLINDNESS) cancel()
        }

        // Rendering is presentation state. Removing these armor stands from the
        // client level used to make the setting irreversible until a respawn
        // packet arrived and could desynchronise other modules. Inspect the
        // fully-updated entity instead and suppress only its current frame.
        on<RenderEvent.Entity> {
            if (!hideFairy && !hideWeaver) return@on
            if (!Dungeon.inDungeons) return@on
            val stand = entity as? ArmorStand ?: return@on
            if (hideFairy && stand.getItemBySlot(EquipmentSlot.MAINHAND).texture == HEALER_FAIRY_TEXTURE) {
                cancel()
                return@on
            }
            if (hideWeaver && stand.getItemBySlot(EquipmentSlot.HEAD).texture == SOUL_WEAVER_TEXTURE) {
                cancel()
            }
        }

        on<GuiEvent.Open.Post> {
            if (!hideRecipeBook) return@on
            Screens.getButtons(screen)
                .filterIsInstance<ImageButton>()
                .firstOrNull { it.textures == RecipeBookComponent.RECIPE_BUTTON_SPRITES }
                ?.visible = false
        }
    }

    @JvmStatic
    fun should(condition: Boolean): Boolean = this.enabled && condition // idkman
}
