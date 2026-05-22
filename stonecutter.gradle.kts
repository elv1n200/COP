// Stonecutter root controller. Selects the active MC version for IDE/dev runs
// and declares source-preprocessor parameters that apply across every versioned
// subproject. The `build.gradle.kts` next to this file runs once per version.
//
// IDE: switch the active version via `./gradlew "Set active version to ..."`
// or by editing the line below and reloading.

plugins {
    id("dev.kikugie.stonecutter")
    // Declaring loom at the controller level (apply false) is what lets
    // Stonecutter 0.9 wire source-preprocessing into each versioned
    // subproject. Without this the raw src/ is compiled and no `replace(...)`
    // / `//?` directives are applied. Version comes from settings
    // pluginManagement. mc26 uses the no-remap (unobfuscated) loom plugin.
    id("net.fabricmc.fabric-loom") apply false
}

stonecutter active "26.1.2"

stonecutter parameters {
    // Constants the source preprocessor can reference inside `//?` comments.
    swaps["mod_id"] = "\"" + project.property("mod_id") + "\""
    swaps["mod_version"] = "\"" + project.property("mod_version") + "\""
    swaps["minecraft"] = "\"" + node.metadata.version + "\""

    // String-level rewrites — applied to `.kt`/`.java` source before compile.
    // 1.21.11 mojmap renamed `ResourceLocation` to `Identifier` (matching the
    // yarn naming Mojang adopted), and shuffled several class packages around.
    // These globals catch the bulk of the simple rename/move cases.
    replacements {
        string(current.parsed >= "1.21.11") {
            replace("ResourceLocation", "Identifier")
            // Package moves — class names unchanged, only the import path differs.
            replace("net.minecraft.Util",                                       "net.minecraft.util.Util")
            replace("net.minecraft.world.entity.monster.CaveSpider",            "net.minecraft.world.entity.monster.spider.CaveSpider")
            replace("net.minecraft.world.entity.monster.Spider",                "net.minecraft.world.entity.monster.spider.Spider")
            replace("net.minecraft.world.entity.monster.Zombie",                "net.minecraft.world.entity.monster.zombie.Zombie")
            replace("net.minecraft.world.entity.projectile.AbstractArrow",      "net.minecraft.world.entity.projectile.arrow.AbstractArrow")
            replace("net.minecraft.client.renderer.RenderType",                 "net.minecraft.client.renderer.rendertype.RenderType")
        }

        // 26.x (unobfuscated, Mojang's real names) renamed/moved further on top
        // of the 1.21.11 changes. These carry the 1.21.11 block above (26 > 1.21)
        // plus 26-only renames. `GuiGraphics` -> `GuiGraphicsExtractor` is a
        // straight class rename so the bare-token replace is safe (source never
        // mentions GuiGraphicsExtractor itself).
        string(current.parsed >= "26") {
            replace("net.minecraft.client.gui.GuiGraphics", "net.minecraft.client.gui.GuiGraphicsExtractor")
            replace("GuiGraphics", "GuiGraphicsExtractor")
            replace("net.minecraft.client.GuiMessage", "net.minecraft.client.multiplayer.chat.GuiMessage")
            // Fabric reworked the world-render API: WorldRenderContext
            // (...rendering.v1.world) -> LevelRenderContext (...v1.level).
            // FQ import first, then the bare type — order matters so the
            // bare rule doesn't corrupt the (already-rewritten) import path.
            replace(
                "net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext",
                "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext",
            )
            replace("WorldRenderContext", "LevelRenderContext")
            // Same world->level rework on the event facades:
            //   WorldRenderEvents (...rendering.v1.world) -> LevelRenderEvents
            //     (...rendering.v1.level) — FQ import first, then bare type.
            //   ClientWorldEvents -> ClientLevelEvents (same lifecycle.v1 pkg, so
            //     the bare rule fixes the import too) + its AFTER_CLIENT_WORLD_-
            //     CHANGE constant -> AFTER_CLIENT_LEVEL_CHANGE.
            replace(
                "net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents",
                "net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents",
            )
            replace("WorldRenderEvents", "LevelRenderEvents")
            replace("ClientWorldEvents", "ClientLevelEvents")
            replace("AFTER_CLIENT_WORLD_CHANGE", "AFTER_CLIENT_LEVEL_CHANGE")
            // Inventory click enum + the gameMode click method were both
            // renamed (same params/values): ClickType -> ContainerInput,
            // handleInventoryMouseClick -> handleContainerInput.
            replace("net.minecraft.world.inventory.ClickType", "net.minecraft.world.inventory.ContainerInput")
            replace("ClickType", "ContainerInput")
            replace("handleInventoryMouseClick", "handleContainerInput")
            // GuiGraphicsExtractor.drawString(...) -> text(...) (same params).
            replace("drawString", "text")
            // Fabric command facade renamed (same package, same literal/
            // argument statics): ClientCommandManager -> ClientCommands.
            replace("ClientCommandManager", "ClientCommands")
            // 26.x folded GuiGraphics' item helpers into the extractor:
            // renderItem(...) -> item(...), renderItemDecorations(...) ->
            // itemDecorations(...). The single `renderItem` rule also catches
            // `renderItemDecorations` (substring) -> `itemDecorations`.
            replace("renderItem", "item")
            // PlayerFaceRenderer -> PlayerFaceExtractor, and its static
            // draw(...) became extractRenderState(...) (same PlayerSkin params).
            // FQ method rule first so the bare class rename can't pre-mangle it.
            replace("PlayerFaceRenderer.draw", "PlayerFaceExtractor.extractRenderState")
            replace("PlayerFaceRenderer", "PlayerFaceExtractor")
            // 26.x GUI deferred-render rework:
            //   Fabric SpecialGuiElementRegistry -> PictureInPictureRendererRegistry
            //     (same v1 pkg; its Context.vertexConsumers() -> bufferSource()).
            //   GuiRenderState.submitPicturesInPictureState -> addPicturesInPictureState.
            //   PictureInPictureRenderState moved gui.render.state.pip ->
            //     renderer.state.gui.pip.
            replace("SpecialGuiElementRegistry", "PictureInPictureRendererRegistry")
            replace("vertexConsumers", "bufferSource")
            replace("submitPicturesInPictureState", "addPicturesInPictureState")
            replace(
                "net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState",
                "net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState",
            )
            // GuiGraphics.submitEntityRenderState(...) -> entity(...) (same 9 args).
            replace("submitEntityRenderState", "entity")
            // GuiTextRenderState moved gui.render.state -> renderer.state.gui.
            // Both dotted (imports) and slash (mixin @At descriptors) forms.
            replace(
                "net.minecraft.client.gui.render.state.GuiTextRenderState",
                "net.minecraft.client.renderer.state.gui.GuiTextRenderState",
            )
            replace(
                "net/minecraft/client/gui/render/state/GuiTextRenderState",
                "net/minecraft/client/renderer/state/gui/GuiTextRenderState",
            )
            // Fabric Screens.getButtons(...) -> getWidgets(...) (List<AbstractWidget>).
            replace("getButtons", "getWidgets")
            // 26.x turned ServerboundInteractPacket into a record and dropped the
            // createInteractionPacket(...) statics. Redirect every caller to our
            // version-bridging InteractPacketCompat (FQ, so no import edits).
            replace(
                "ServerboundInteractPacket.createInteractionPacket",
                "cop.utils.skyblock.player.interact.InteractPacketCompat.createInteractionPacket",
            )
        }
    }
}

// Root-level aggregator — builds every versioned subproject's `buildAndCollect`
// task. Stonecutter 0.8 doesn't ship a built-in `chiseledBuild` task, so we
// register our own equivalent here.
tasks.register("buildAll") {
    group = "build"
    description = "Builds every versioned subproject and collects the jars in ./dist/."
    dependsOn(stonecutter.tree.nodes.map { ":${it.metadata.version}:buildAndCollect" })
}
