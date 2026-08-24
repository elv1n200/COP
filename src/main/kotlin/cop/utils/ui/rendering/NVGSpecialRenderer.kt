package cop.utils.ui.rendering

import cop.CopMod.mc
import com.mojang.blaze3d.opengl.GlStateManager
// 26.x made GlDevice package-private and removed GlConst; the old direct
// device cast used below therefore remains on the 1.21.x branch.
//? if <= 1.21.11 {
/*import com.mojang.blaze3d.opengl.GlConst
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlTexture
*///? }
import com.mojang.blaze3d.systems.RenderSystem
// 26.x: the offscreen FBO is reached via the (public) DirectStateAccess +
// GpuTexture(View) types. GlDevice is package-private, so obtaining its
// DirectStateAccess still needs a narrow reflective bridge on this branch.
//? if >= 26 {
/*import com.mojang.blaze3d.opengl.DirectStateAccess
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat*/
//? }
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.navigation.ScreenRectangle
//? if >= 26 {
/*import net.minecraft.client.gui.render.TextureSetup*/
//? }
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.MultiBufferSource
//? if >= 26 {
/*import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.state.gui.BlitRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState*/
//? }
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
    /*// Mojang's renderer owns just one color texture, but GuiRenderer prepares every
    // PIP state before it draws any blit. Reusing that texture therefore makes all
    // NVG blits in a frame sample the last rendered state. Keep a color slot per
    // same-frame state, while sharing a freshly-cleared depth target by dimensions.
    private val targetPool = FrameTargetPool<ColorTarget, DepthTarget>(
        createColor = ::createColorTarget,
        colorSize = { it.size },
        createDepth = ::createDepthTarget,
    )

    // 26.x's base PictureInPictureRenderer does not bind a GL framebuffer before
    // renderToTexture, so NanoVG's raw-GL draws would miss its offscreen target.
    // GlDevice is package-private, so only directStateAccess needs narrowly-
    // scoped reflection. GlTexture/getFbo themselves are public in 26.1.2.
    private var backendField: java.lang.reflect.Field? = null
    private var dsaMethod: java.lang.reflect.Method? = null
    private var fboErrLogged = false
    private var rendererClosed = false

    override fun prepare(state: NVGRenderState, guiState: GuiRenderState, windowScaleFactor: Int) {
        if (windowScaleFactor <= 0) return
        val physicalWidth = (state.x1().toLong() - state.x0().toLong()) * windowScaleFactor.toLong()
        val physicalHeight = (state.y1().toLong() - state.y0().toLong()) * windowScaleFactor.toLong()
        if (physicalWidth !in 1..Int.MAX_VALUE.toLong() || physicalHeight !in 1..Int.MAX_VALUE.toLong()) return
        val width = physicalWidth.toInt()
        val height = physicalHeight.toInt()

        val targets = targetPool.acquire(state.frameToken, TargetSize(width, height))
        val previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        val previousViewport = IntArray(4)
        glGetIntegerv(GL_VIEWPORT, previousViewport)
        val rendered = try {
            RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(
                targets.color.texture,
                0,
                targets.depth.texture,
                1.0,
            )

            val previousColorOverride = RenderSystem.outputColorTextureOverride
            val previousDepthOverride = RenderSystem.outputDepthTextureOverride
            try {
                RenderSystem.outputColorTextureOverride = targets.color.view
                RenderSystem.outputDepthTextureOverride = targets.depth.view
                val succeeded = renderCurrentTarget(state)
                if (succeeded) bufferSource.endBatch()
                succeeded
            } finally {
                RenderSystem.outputColorTextureOverride = previousColorOverride
                RenderSystem.outputDepthTextureOverride = previousDepthOverride
            }
        } finally {
            try {
                GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer)
                GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer)
            } finally {
                GlStateManager._viewport(
                    previousViewport[0],
                    previousViewport[1],
                    previousViewport[2],
                    previousViewport[3],
                )
            }
        }
        if (!rendered) return

        guiState.addBlitToCurrentLayer(
            BlitRenderState(
                RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
                TextureSetup.singleTexture(
                    targets.color.view,
                    RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST),
                ),
                state.pose(),
                state.x0(),
                state.y0(),
                state.x1(),
                state.y1(),
                0f,
                1f,
                1f,
                0f,
                -1,
                state.scissorArea(),
                null,
            ),
        )
    }

    override fun renderToTexture(state: NVGRenderState, poseStack: PoseStack) {
        renderCurrentTarget(state)
    }

    private fun renderCurrentTarget(state: NVGRenderState): Boolean {
        val colorView = RenderSystem.outputColorTextureOverride ?: return false
        val depthTex = RenderSystem.outputDepthTextureOverride?.texture() ?: return false
        val width = colorView.getWidth(0)
        val height = colorView.getHeight(0)

        val previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)
        val previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING)
        val previousViewport = IntArray(4)
        glGetIntegerv(GL_VIEWPORT, previousViewport)
        val previousSampler = glGetInteger(GL_SAMPLER_BINDING)

        var frameStarted = false
        try {
            val fbo = fboFor(colorView.texture(), depthTex) ?: return false
            GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, fbo)
            GlStateManager._viewport(0, 0, width, height)
            // RenderPipeline's unit-0 sampler turns NVG glyphs into white blocks.
            glBindSampler(0, 0)

            NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
            frameStarted = true
            state.renderContent()
            return true
        } finally {
            try {
                if (frameStarted) NVGRenderer.endFrame()
            } finally {
                try {
                    GlStateManager._disableDepthTest()
                    GlStateManager._disableCull()
                    GlStateManager._enableBlend()
                    GlStateManager._blendFuncSeparate(770, 771, 1, 0)
                } finally {
                    try {
                        glBindSampler(0, previousSampler)
                    } finally {
                        try {
                            GlStateManager._glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer)
                            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer)
                        } finally {
                            GlStateManager._viewport(
                                previousViewport[0],
                                previousViewport[1],
                                previousViewport[2],
                                previousViewport[3],
                            )
                        }
                    }
                }
            }
        }
    }

    private fun createColorTarget(slot: Int, size: TargetSize): ColorTarget {
        val device = RenderSystem.getDevice()
        val texture = device.createTexture(
            "nvg_renderer_color_$slot",
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT,
            TextureFormat.RGBA8,
            size.width,
            size.height,
            1,
            1,
        )
        return try {
            ColorTarget(size, texture, device.createTextureView(texture))
        } catch (error: Throwable) {
            texture.close()
            throw error
        }
    }

    private fun createDepthTarget(size: TargetSize): DepthTarget {
        val device = RenderSystem.getDevice()
        val texture = device.createTexture(
            "nvg_renderer_depth_${size.width}x${size.height}",
            GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_RENDER_ATTACHMENT,
            TextureFormat.DEPTH32,
            size.width,
            size.height,
            1,
            1,
        )
        return try {
            DepthTarget(texture, device.createTextureView(texture))
        } catch (error: Throwable) {
            texture.close()
            throw error
        }
    }

    private fun fboFor(colorTexture: GpuTexture, depthTex: GpuTexture): Int? = try {
        val glColorTexture = colorTexture as? GlTexture
            ?: error("NVG offscreen color target is not an OpenGL texture")
        val device = RenderSystem.getDevice()
        if (dsaMethod == null) {
            backendField = device.javaClass.getDeclaredField("backend").apply { isAccessible = true }
            val backend = backendField!!.get(device)
            dsaMethod = backend.javaClass.getMethod("directStateAccess").apply { isAccessible = true }
        }
        val backend = backendField!!.get(device)
        val dsa = dsaMethod!!.invoke(backend) as DirectStateAccess
        glColorTexture.getFbo(dsa, depthTex).takeIf { it > 0 }
            ?: error("NVG offscreen framebuffer creation returned the default framebuffer")
    } catch (e: Throwable) {
        if (!fboErrLogged) { fboErrLogged = true; cop.CopMod.logger.error("[cop] NVG offscreen FBO bind failed", e) }
        null
    }

    override fun close() {
        if (rendererClosed) return
        rendererClosed = true
        try {
            targetPool.close()
        } finally {
            super.close()
        }
    }

    private data class ColorTarget(
        val size: TargetSize,
        val texture: GpuTexture,
        val view: GpuTextureView,
    ) : AutoCloseable {
        override fun close() {
            try {
                texture.close()
            } finally {
                view.close()
            }
        }
    }

    private data class DepthTarget(
        val texture: GpuTexture,
        val view: GpuTextureView,
    ) : AutoCloseable {
        override fun close() {
            try {
                texture.close()
            } finally {
                view.close()
            }
        }
    }

    internal data class TargetSize(val width: Int, val height: Int) {
        init {
            require(width > 0 && height > 0) { "NVG target dimensions must be positive" }
        }
    }

    internal data class FrameTargets<C, D>(val color: C, val depth: D)

    internal class FrameTargetPool<C : AutoCloseable, D : AutoCloseable>(
        private val createColor: (slot: Int, size: TargetSize) -> C,
        private val colorSize: (C) -> TargetSize,
        private val createDepth: (size: TargetSize) -> D,
    ) : AutoCloseable {
        private val colors = mutableListOf<C>()
        private val depths = mutableMapOf<TargetSize, D>()
        private val usedDepthSizes = mutableSetOf<TargetSize>()
        private var frameToken: java.lang.ref.WeakReference<Any>? = null
        private var nextSlot = 0
        private var closed = false

        fun acquire(token: Any, size: TargetSize): FrameTargets<C, D> {
            check(!closed) { "NVG frame target pool is closed" }
            beginFrame(token)

            val slot = nextSlot
            val color = colorFor(slot, size)
            val depth = depths.getOrPut(size) { createDepth(size) }
            usedDepthSizes += size
            nextSlot++
            return FrameTargets(color, depth)
        }

        private fun beginFrame(token: Any) {
            if (frameToken?.get() === token) return

            if (frameToken != null) {
                while (colors.size > nextSlot) {
                    colors.removeLast().close()
                }

                val iterator = depths.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (entry.key !in usedDepthSizes) {
                        iterator.remove()
                        entry.value.close()
                    }
                }
            }

            frameToken = java.lang.ref.WeakReference(token)
            nextSlot = 0
            usedDepthSizes.clear()
        }

        private fun colorFor(slot: Int, size: TargetSize): C {
            if (slot == colors.size) {
                return createColor(slot, size).also(colors::add)
            }

            val current = colors[slot]
            if (colorSize(current) == size) return current

            val replacement = createColor(slot, size)
            colors[slot] = replacement
            current.close()
            return replacement
        }

        override fun close() {
            if (closed) return
            closed = true

            var failure: Throwable? = null
            fun closeResource(resource: AutoCloseable) {
                try {
                    resource.close()
                } catch (error: Throwable) {
                    val previousFailure = failure
                    if (previousFailure == null) failure = error else previousFailure.addSuppressed(error)
                }
            }

            colors.forEach(::closeResource)
            depths.values.forEach(::closeResource)
            colors.clear()
            depths.clear()
            usedDepthSizes.clear()
            frameToken = null
            nextSlot = 0

            failure?.let { throw it }
        }
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

        var frameStarted = false
        try {
            NVGRenderer.beginFrame(width.toFloat(), height.toFloat())
            frameStarted = true
            state.renderContent()
        } finally {
            try {
                if (frameStarted) NVGRenderer.endFrame()
            } finally {
                GlStateManager._disableDepthTest()
                GlStateManager._disableCull()
                GlStateManager._enableBlend()
                GlStateManager._blendFuncSeparate(770, 771, 1, 0)

                //? if >= 1.21.11 {
                /*glBindSampler(0, prevSampler)
                *///? }
            }
        }
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
        // GuiGraphicsExtractor is recreated once per extracted frame in 26.x.
        // Its identity groups all NVG states that must own distinct color slots.
        //? if >= 26 {
        /*val frameToken: Any,*/
        //? }
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
                //? if >= 26 {
                /*context,*/
                //? }
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
