package cop.utils.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
//? if >= 26 {
/*import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.platform.CompareOp*/
//? } else {
import com.mojang.blaze3d.platform.DepthTestFunction
//? }
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines

/**
 * from OdinFabric (BSD 3-Clause)
 * copyright (c) 2025-2026 odtheking
 * original: https://github.com/odtheking/OdinFabric/blob/main/src/main/kotlin/com/odtheking/odin/utils/render/CustomRenderPipelines.kt
 *
 * The vertex format on the LINE_* pipelines is intentionally **not** overridden.
 * 1.21.11 introduced `POSITION_COLOR_NORMAL_LINE_WIDTH` and the per-vertex
 * `setLineWidth()` shader path, while 1.21.10 still uses `POSITION_COLOR_NORMAL`.
 * `LINES_SNIPPET` already wires the right format for whichever version we're
 * compiled against, so leaving the format unset prevents the snippet's choice
 * from being clobbered.
 *
 * 26.x reworked the fixed-function pipeline state on `RenderPipeline.Builder`:
 * the standalone `withBlend(BlendFunction)` / `withDepthWrite(boolean)` /
 * `withDepthTestFunction(DepthTestFunction)` setters were folded into the
 * `ColorTargetState` and `DepthStencilState` records. The two compat helpers
 * below isolate that rename so the four pipeline definitions stay version-agnostic.
 */
object CustomRenderPipelines {

    // --- 26.x state-record shims --------------------------------------------
    // pre-26: discrete setters. 26+: ColorTargetState / DepthStencilState records.
    private fun RenderPipeline.Builder.withTranslucentBlend(): RenderPipeline.Builder {
        //? if >= 26 {
        /*return withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))*/
        //? } else {
        return withBlend(BlendFunction.TRANSLUCENT)
        //? }
    }

    /** [write] = depth-write enabled; [lequal] = LEQUAL depth test (false = no depth test / always pass). */
    private fun RenderPipeline.Builder.withDepth(write: Boolean, lequal: Boolean): RenderPipeline.Builder {
        //? if >= 26 {
        /*return withDepthStencilState(
            DepthStencilState(if (lequal) CompareOp.LESS_THAN_OR_EQUAL else CompareOp.ALWAYS_PASS, write)
        )*/
        //? } else {
        return withDepthWrite(write)
            .withDepthTestFunction(if (lequal) DepthTestFunction.LEQUAL_DEPTH_TEST else DepthTestFunction.NO_DEPTH_TEST)
        //? }
    }

    val LINE_LIST: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.LINES_SNIPPET))
            .withLocation("cop/pipeline/lines")
            .withCull(false)
            .withTranslucentBlend()
            .withDepth(write = true, lequal = true)
            .build()
    )

    val LINE_LIST_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.LINES_SNIPPET))
            .withLocation("cop/pipeline/lines_esp")
            .withShaderDefine("shad")
            .withCull(false)
            .withTranslucentBlend()
            .withDepth(write = false, lequal = false)
            .build()
    )

    val TRIANGLE_STRIP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/debug_filled_box")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLE_STRIP)
            .withDepth(write = true, lequal = true)
            .withTranslucentBlend()
            .build()
    )

    val TRIANGLE_STRIP_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/debug_filled_box_esp")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLE_STRIP)
            .withDepth(write = false, lequal = false)
            .withTranslucentBlend()
            .build()
    )
}
