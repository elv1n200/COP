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
 * Lines and wireframes are rendered as camera-facing quads (not GL_LINES) so
 * that Iris/Sodium shader packs actually pick them up — those backends drop
 * line primitives. The `BILLBOARD_LINE_QUAD` pipelines pair with
 * `POSITION_COLOR` + `QUADS`; the emitter side does the cross-product math
 * (`WorldRenderContextUtils.billboardLineQuad`) to orient the quad. The
 * `TRIANGLE_STRIP*` pipelines were already shader-friendly and serve the
 * filled-box path unchanged.
 *
 * 26.x reworked the fixed-function pipeline state on `RenderPipeline.Builder`:
 * the standalone `withBlend(BlendFunction)` / `withDepthWrite(boolean)` /
 * `withDepthTestFunction(DepthTestFunction)` setters were folded into the
 * `ColorTargetState` and `DepthStencilState` records. The two compat helpers
 * below isolate that rename so the pipeline definitions stay version-agnostic.
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

    val BILLBOARD_LINE_QUAD: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/billboard_line_quad")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withTranslucentBlend()
            .withDepth(write = true, lequal = true)
            .build()
    )

    val BILLBOARD_LINE_QUAD_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/billboard_line_quad_esp")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
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
