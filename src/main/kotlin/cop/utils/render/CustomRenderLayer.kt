package cop.utils.render

import net.minecraft.client.renderer.RenderType
//? if >= 1.21.11 {
/*import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.LayeringTransform
*///?}

/**
 * from OdinFabric (BSD 3-Clause)
 * copyright (c) 2025-2026 odtheking
 * original: https://github.com/odtheking/OdinFabric/blob/main/src/main/kotlin/com/odtheking/odin/utils/render/CustomRenderLayer.kt
 *
 * 1.21.11 redesigned `RenderType.create` — instead of `(name, bufferSize,
 * affectsCrumbling, sortOnUpload, pipeline, compositeState)` it now takes
 * `(name, RenderSetup)` where the setup is built via `RenderSetup.builder(pipeline)`.
 * Layering moved off `RenderType.VIEW_OFFSET_Z_LAYERING` onto a dedicated
 * `LayeringTransform` class. The two branches below produce the same logical
 * RenderType on either version.
 */
object CustomRenderLayer {

    val LINE_LIST: RenderType =
        //? if >= 1.21.11 {
        /*RenderType.create(
            "line-list",
            RenderSetup.builder(CustomRenderPipelines.LINE_LIST)
                .bufferSize(RenderType.TRANSIENT_BUFFER_SIZE)
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .createRenderSetup()
        )
        *///? } else {
        RenderType.create(
            "line-list",
            RenderType.TRANSIENT_BUFFER_SIZE,
            CustomRenderPipelines.LINE_LIST,
            RenderType.CompositeState.builder()
                .setLayeringState(RenderType.VIEW_OFFSET_Z_LAYERING)
                .createCompositeState(false)
        )
        //? }

    val LINE_LIST_ESP: RenderType =
        //? if >= 1.21.11 {
        /*RenderType.create(
            "line-list-esp",
            RenderSetup.builder(CustomRenderPipelines.LINE_LIST_ESP)
                .bufferSize(RenderType.TRANSIENT_BUFFER_SIZE)
                .createRenderSetup()
        )
        *///? } else {
        RenderType.create(
            "line-list-esp",
            RenderType.TRANSIENT_BUFFER_SIZE,
            CustomRenderPipelines.LINE_LIST_ESP,
            RenderType.CompositeState.builder().createCompositeState(false)
        )
        //? }

    val TRIANGLE_STRIP: RenderType =
        //? if >= 1.21.11 {
        /*RenderType.create(
            "triangle_strip",
            RenderSetup.builder(CustomRenderPipelines.TRIANGLE_STRIP)
                .bufferSize(RenderType.TRANSIENT_BUFFER_SIZE)
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .sortOnUpload()
                .createRenderSetup()
        )
        *///? } else {
        RenderType.create(
            "triangle_strip",
            RenderType.TRANSIENT_BUFFER_SIZE,
            false,
            true,
            CustomRenderPipelines.TRIANGLE_STRIP,
            RenderType.CompositeState.builder()
                .setLayeringState(RenderType.VIEW_OFFSET_Z_LAYERING)
                .createCompositeState(false)
        )
        //? }

    val TRIANGLE_STRIP_ESP: RenderType =
        //? if >= 1.21.11 {
        /*RenderType.create(
            "triangle_strip_esp",
            RenderSetup.builder(CustomRenderPipelines.TRIANGLE_STRIP_ESP)
                .bufferSize(RenderType.TRANSIENT_BUFFER_SIZE)
                .sortOnUpload()
                .createRenderSetup()
        )
        *///? } else {
        RenderType.create(
            "triangle_strip_esp",
            RenderType.TRANSIENT_BUFFER_SIZE,
            false,
            true,
            CustomRenderPipelines.TRIANGLE_STRIP_ESP,
            RenderType.CompositeState.builder().createCompositeState(false)
        )
        //? }
}
