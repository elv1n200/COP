package cop

import kotlinx.coroutines.CoroutineScope
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.rendering.v1.SpecialGuiElementRegistry
import net.minecraft.client.Minecraft
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import cop.annotations.AnnotationLoader
import cop.api.commands.CopCommand
import cop.api.events.GameEvent
import cop.api.events.core.EventBus
import cop.config.Config
import cop.module.ModuleManager
import cop.utils.ui.hud.HudManager
import cop.utils.ui.rendering.NVGSpecialRenderer
import kotlin.coroutines.EmptyCoroutineContext

object CopMod : ClientModInitializer {

    const val MOD_ID = "cop"
    val mc: Minecraft get() = Minecraft.getInstance()
    val scope = CoroutineScope(EmptyCoroutineContext)
    val logger: Logger = LogManager.getLogger("cop")

    override fun onInitializeClient() {
        ModuleManager.initialise()
        AnnotationLoader.load()
        SpecialGuiElementRegistry.register { context ->
            NVGSpecialRenderer(context.vertexConsumers())
        }

        var schizophrenia: EventBus.EventListener? = null
        schizophrenia = EventBus.on<GameEvent.Load> {
            HudManager.init()
            schizophrenia?.remove()
        }
        CopCommand.init()
        Config.load()
    }
}