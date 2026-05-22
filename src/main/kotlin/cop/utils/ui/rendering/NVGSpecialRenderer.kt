package cop.utils.ui.rendering

import cop.CopMod.mc
import com.mojang.blaze3d.opengl.GlStateManager
// 26.x made GlDevice/GlTexture/GlConst package-private/removed — the manual
// FBO-rebind trick they enabled only exists on the 1.21.x branch.
//? if <= 1.21.11 {
/*import com.mojang.blaze3d.opengl.GlConst
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlTexture
*///? }
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.MultiBufferSource
import org.joml.Matrix3x2f
//? if >= 1.21.11
//import org.lwjgl.opengl.GL33.*

/**
 * from OdinFabric (BSD 3-Clause)
 * copyright (c) 2025-2026 odtheking
 * original: https://github.com/odtheking/OdinFabric/blob/main/src/main/kotlin/com/odtheking/odin/utils/ui/rendering/NVGPIPRenderer.kt
 */
class NVGSpecialRenderer(vertexConsumers: MultiBufferSource.BufferSource)
    : PictureInPictureRenderer<NVGSpecialRenderer.NVGRenderState>(vertexConsumers) {

    //? if >= 26 {
    /*// 26.x encapsulated the GL device/texture internals (GlDevice, GlTexture,
    // GlConst all package-private/removed), so the 1.21.x manual FBO-rebind is
    // no longer reachable. The base PictureInPictureRenderer already binds the
    // offscreen target before calling renderToTexture, so NVG draws straight
    // into it. NOTE: needs in-game verification on 26.x (glyph sampler state).
    override fun renderToTexture(state: NVGRenderState, poseStack: PoseStack) {
        val colorTex = RenderSystem.outputColorTextureOverride ?: return
        val width = colorTex.getWidth(0)
        val height = colorTex.getHeight(0)

        NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
        state.renderContent()
        NVGRenderer.endFrame()

        GlStateManager._disableDepthTest()
        GlStateManager._disableCull()
        GlStateManager._enableBlend()
        GlStateManager._blendFuncSeparate(770, 771, 1, 0)
    }*/
    //? } else {
    override fun renderToTexture(state: NVGRenderState, poseStack: PoseStack) {
        val colorTex = RenderSystem.outputColorTextureOverride
        val bufferManager = (RenderSystem.getDevice() as? GlDevice)?.directStateAccess() ?: return
        val glDepthTex = (RenderSystem.outputDepthTextureOverride?.texture() as? GlTexture) ?: return

        val width = colorTex?.getWidth(0) ?: return
        val height = colorTex.getHeight(0)
        (colorTex.texture() as? GlTexture)?.getFbo(bufferManager, glDepthTex)?.apply {
            GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, this)
            GlStateManager._viewport(0, 0, width, height)
        }

        // 1.21.11's RenderPipeline binds a GL sampler on unit 0 that overrides
        // the texture-object sampler state NVG sets per-glyph — without this,
        // text glyphs render as solid white blocks. Save+null+restore the
        // sampler around NVG's draw to keep both pipelines happy.
        //? if >= 1.21.11 {
        /*val prevSampler = glGetInteger(GL_SAMPLER_BINDING)
        glBindSampler(0, 0)
        *///? }

        NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
        state.renderContent()
        NVGRenderer.endFrame()

        GlStateManager._disableDepthTest()
        GlStateManager._disableCull()
        GlStateManager._enableBlend()
        GlStateManager._blendFuncSeparate(770, 771, 1, 0)

        //? if >= 1.21.11 {
        /*glBindSampler(0, prevSampler)
        *///? }
    }
    //? }

    override fun getTranslateY(height: Int, windowScaleFactor: Int): Float = height / 2f
    override fun getRenderStateClass(): Class<NVGRenderState> = NVGRenderState::class.java
    override fun getTextureLabel(): String = "nvg_renderer"

    data class NVGRenderState(
        private val x: Int,
        private val y: Int,
        private val width: Int,
        private val height: Int,
        private val poseMatrix: Matrix3x2f,
        private val scissor: ScreenRectangle?,
        private val bounds: ScreenRectangle?,
        val renderContent: () -> Unit
    ) : PictureInPictureRenderState {

        override fun scale(): Float = 1f
        override fun x0(): Int = x
        override fun y0(): Int = y
        override fun x1(): Int = x + width
        override fun y1(): Int = y + height
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds(): ScreenRectangle? = bounds
        // 26.x added PictureInPictureRenderState.pose() (default IDENTITY_POSE);
        // feed it our stored matrix so the offscreen draw is positioned right.
        //? if >= 26 {
        /*override fun pose(): Matrix3x2f = poseMatrix*/
        //? }
    }

    companion object {
        /**
         * Draw NVG content as a special GUI element.
         *
         * @param context The GuiGraphics to draw to
         * @param x The x position
         * @param y The y position
         * @param width The width of the rendering area
         * @param height The height of the rendering area
         * @param renderContent A lambda that draws the NVG content
         */
        fun draw(
            context: GuiGraphics,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            renderContent: () -> Unit
        ) {
            val scissor = context.scissorStack.peek()
            val pose = Matrix3x2f(context.pose())
            val bounds = createBounds(x, y, x + width, y + height, pose, scissor)

            val state = NVGRenderState(
                x, y, width, height,
                pose, scissor, bounds,
                renderContent
            )
            context.guiRenderState.submitPicturesInPictureState(state)
        }

        private fun createBounds(x0: Int, y0: Int, x1: Int, y1: Int, pose: Matrix3x2f, scissorArea: ScreenRectangle?): ScreenRectangle? {
            val screenRect = ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(pose)
            return if (scissorArea != null) scissorArea.intersection(screenRect) else screenRect
        }
    }
}