package cop.utils.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines

/**
 * Lines and wireframes are rendered as camera-facing quads (not GL_LINES) so
 * that Iris/Sodium shader packs actually pick them up — those backends drop
 * line primitives. The `BILLBOARD_LINE_QUAD` pipelines pair with
 * `POSITION_COLOR` + `QUADS` since the emitter side already does the
 * cross-product math to orient the quad. See
 * `WorldRenderContextUtils.billboardLineQuad` for the geometry.
 *
 * `TRIANGLE_STRIP*` is unchanged and still serves the filled-box path; it was
 * already shader-friendly.
 */
object CustomRenderPipelines {

    val BILLBOARD_LINE_QUAD: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/billboard_line_quad")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(true)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .build()
    )

    val BILLBOARD_LINE_QUAD_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/billboard_line_quad_esp")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .build()
    )

    val TRIANGLE_STRIP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/debug_filled_box")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLE_STRIP)
            .withDepthWrite(true)
            .withDepthTestFunction(DepthTestFunction.LEQUAL_DEPTH_TEST)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build()
    )

    val TRIANGLE_STRIP_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(*arrayOf<RenderPipeline.Snippet>(RenderPipelines.DEBUG_FILLED_SNIPPET))
            .withLocation("cop/pipeline/debug_filled_box_esp")
            .withCull(false)
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.TRIANGLE_STRIP)
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withBlend(BlendFunction.TRANSLUCENT)
            .build()
    )
}
