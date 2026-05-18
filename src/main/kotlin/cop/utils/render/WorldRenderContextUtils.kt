package cop.utils.render

import cop.CopMod.mc
import cop.api.colour.*
import cop.mixins.accessors.CameraAccessor
import cop.utils.EntityUtils.renderPos
import cop.utils.unaryMinus
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.ByteBufferBuilder
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext
import net.minecraft.client.gui.Font
//? if <= 1.21.11
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * from OdinFabric (BSD 3-Clause)
 * copyright (c) 2025-2026 odtheking
 * original: no longer exists, fuck off
 *
 * The vertex helpers below replace `ShapeRenderer.renderVector` /
 * `renderLineBox` / `addChainedFilledBoxVertices` which were removed in
 * 1.21.11 alongside the `RenderSystem.lineWidth` migration to per-vertex
 * `setLineWidth`. Writing them out by hand keeps a single source path that
 * compiles for both 1.21.10 and 1.21.11.
 */
private val ALLOCATOR = ByteBufferBuilder(1536)

private fun camera() = mc.gameRenderer.mainCamera

// `Camera.position` is a public field in 1.21.10 but became private in 1.21.11.
// The mixin accessor exposes it through one signature on both versions.
private val net.minecraft.client.Camera.pos: Vec3
    get() = (this as CameraAccessor).position

/** Emits two line vertices with the given colour. */
private fun PoseStack.Pose.lineVertex(
    buffer: VertexConsumer,
    x1: Float, y1: Float, z1: Float,
    x2: Float, y2: Float, z2: Float,
    colour: Int,
    width: Float,
) {
    val dx = x2 - x1; val dy = y2 - y1; val dz = z2 - z1
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    val nx = if (len > 0) dx / len else 0f
    val ny = if (len > 0) dy / len else 0f
    val nz = if (len > 0) dz / len else 0f
    // `setLineWidth` only exists on 1.21.11+; on 1.21.10 line width is a
    // global GL state set via `RenderSystem.lineWidth()` before the draw call.
    buffer.addVertex(this, x1, y1, z1).setColor(colour).setNormal(this, nx, ny, nz)
        //? if >= 1.21.11
        /*.setLineWidth(width)*/
    buffer.addVertex(this, x2, y2, z2).setColor(colour).setNormal(this, nx, ny, nz)
        //? if >= 1.21.11
        /*.setLineWidth(width)*/
}

/** Suppress the now-unused [width] warning on 1.21.10 — referenced only above. */
@Suppress("unused")
private val widthIsUsedOn1_21_11 = Unit

/** Wireframe box — 12 edges, 24 vertices. */
private fun PoseStack.Pose.lineBox(buffer: VertexConsumer, box: AABB, colour: Int, width: Float) {
    val x1 = box.minX.toFloat(); val y1 = box.minY.toFloat(); val z1 = box.minZ.toFloat()
    val x2 = box.maxX.toFloat(); val y2 = box.maxY.toFloat(); val z2 = box.maxZ.toFloat()
    // bottom rectangle
    lineVertex(buffer, x1, y1, z1, x2, y1, z1, colour, width)
    lineVertex(buffer, x2, y1, z1, x2, y1, z2, colour, width)
    lineVertex(buffer, x2, y1, z2, x1, y1, z2, colour, width)
    lineVertex(buffer, x1, y1, z2, x1, y1, z1, colour, width)
    // top rectangle
    lineVertex(buffer, x1, y2, z1, x2, y2, z1, colour, width)
    lineVertex(buffer, x2, y2, z1, x2, y2, z2, colour, width)
    lineVertex(buffer, x2, y2, z2, x1, y2, z2, colour, width)
    lineVertex(buffer, x1, y2, z2, x1, y2, z1, colour, width)
    // pillars
    lineVertex(buffer, x1, y1, z1, x1, y2, z1, colour, width)
    lineVertex(buffer, x2, y1, z1, x2, y2, z1, colour, width)
    lineVertex(buffer, x2, y1, z2, x2, y2, z2, colour, width)
    lineVertex(buffer, x1, y1, z2, x1, y2, z2, colour, width)
}

/** Filled box as triangle-strip — 14 vertices (degenerate-tri terminated strip). */
private fun PoseStack.Pose.filledBoxStrip(buffer: VertexConsumer, box: AABB, colour: Int) {
    val x1 = box.minX.toFloat(); val y1 = box.minY.toFloat(); val z1 = box.minZ.toFloat()
    val x2 = box.maxX.toFloat(); val y2 = box.maxY.toFloat(); val z2 = box.maxZ.toFloat()
    // Standard cube triangle-strip: emit 14 verts walking the cube faces.
    val v = arrayOf(
        floatArrayOf(x1, y2, z1), floatArrayOf(x2, y2, z1),
        floatArrayOf(x1, y1, z1), floatArrayOf(x2, y1, z1),
        floatArrayOf(x1, y1, z2), floatArrayOf(x2, y1, z2),
        floatArrayOf(x1, y2, z2), floatArrayOf(x2, y2, z2),
        floatArrayOf(x1, y2, z1), floatArrayOf(x2, y2, z1),
        floatArrayOf(x2, y2, z2), floatArrayOf(x2, y1, z1),
        floatArrayOf(x2, y1, z2), floatArrayOf(x1, y1, z2),
    )
    for (p in v) buffer.addVertex(this, p[0], p[1], p[2]).setColor(colour)
}

// Shorthand: `RenderSystem.lineWidth` is gone in 1.21.11. Wrap the call so we
// can no-op on the new version where line width is set per-vertex above.
@Suppress("DEPRECATION")
private fun setLineWidthIfSupported(width: Float) {
    //? if <= 1.21.10 {
    RenderSystem.lineWidth(width)
    //?}
}

// 26.x reworked Fabric's world-render context: WorldRenderContext.matrices()
// /consumers() became LevelRenderContext.poseStack()/bufferSource(). The
// receiver type itself is swapped by the Stonecutter `WorldRenderContext` ->
// `LevelRenderContext` replacement; these compat shims isolate the method
// rename to one place each.
private fun WorldRenderContext.poseStackCompat(): PoseStack? {
    //? if >= 26 {
    /*return poseStack()*/
    //? } else {
    return matrices()
    //? }
}

private fun WorldRenderContext.bufferSourceCompat(): MultiBufferSource.BufferSource? {
    //? if >= 26 {
    /*return bufferSource() as? MultiBufferSource.BufferSource*/
    //? } else {
    return consumers() as? MultiBufferSource.BufferSource
    //? }
}

fun WorldRenderContext.drawLine(points: Collection<Vec3>, colour: Colour, depth: Boolean, thickness: Float = 3f) {
    if (points.size < 2) return
    val matrix = poseStackCompat() ?: return
    val bufferSource = bufferSourceCompat() ?: return
    val layer = if (depth) CustomRenderLayer.LINE_LIST else CustomRenderLayer.LINE_LIST_ESP
    setLineWidthIfSupported(thickness)

    matrix.pushPose()
    with(camera().pos) { matrix.translate(-x, -y, -z) }

    val pose = matrix.last()
    val buffer = bufferSource.getBuffer(layer)
    val pointList = points.toList()
    for (i in 0 until pointList.size - 1) {
        val s = pointList[i]
        val e = pointList[i + 1]
        pose.lineVertex(buffer, s.x.toFloat(), s.y.toFloat(), s.z.toFloat(),
                                e.x.toFloat(), e.y.toFloat(), e.z.toFloat(),
                                colour.rgb, thickness)
    }

    matrix.popPose()
    bufferSource.endBatch(layer)
}

fun WorldRenderContext.drawTracer(to: Vec3, colour: Colour, thickness: Float = 6f, depth: Boolean = false) {
    val from = mc.player?.let { player ->
        player.renderPos.add(player.forward.add(0.0, player.eyeHeight.toDouble(), 0.0))
    } ?: return
    drawLine(listOf(from, to), colour, depth, thickness)
}

fun WorldRenderContext.drawWireFrameBox(aabb: AABB, colour: Colour, thickness: Float = 6f, depth: Boolean = false) {
    val matrix = poseStackCompat() ?: return
    val bufferSource = bufferSourceCompat() ?: return
    val layer = if (depth) CustomRenderLayer.LINE_LIST else CustomRenderLayer.LINE_LIST_ESP
    val camera = camera() ?: return
    val width = (thickness / camera.pos.distanceToSqr(aabb.center).pow(0.15)).toFloat()
    setLineWidthIfSupported(width)

    matrix.pushPose()
    with(camera.pos) { matrix.translate(-x, -y, -z) }
    matrix.last().lineBox(bufferSource.getBuffer(layer), aabb, colour.rgb, width)
    matrix.popPose()
    bufferSource.endBatch(layer)
}

fun WorldRenderContext.drawFilledBox(box: AABB, colour: Colour, depth: Boolean = false) {
    val matrix = poseStackCompat() ?: return
    val bufferSource = bufferSourceCompat() ?: return
    val layer = if (depth) CustomRenderLayer.TRIANGLE_STRIP else CustomRenderLayer.TRIANGLE_STRIP_ESP

    matrix.pushPose()
    with(camera().pos) { matrix.translate(-x, -y, -z) }
    matrix.last().filledBoxStrip(bufferSource.getBuffer(layer), box, colour.rgb)
    matrix.popPose()
    bufferSource.endBatch(layer)
}

fun WorldRenderContext.drawStyledBox(style: String, box: AABB, colour: Colour, fillColour: Colour = colour, thickness: Float = 2.0f, depth: Boolean = false) {
    when (style) {
        "Box" -> drawWireFrameBox(box, colour, thickness, depth)
        "Filled box" -> {
            drawFilledBox(box, fillColour, depth)
            drawWireFrameBox(box, colour, thickness, depth)
        }
    }
}

//fun WorldRenderContext.drawBeaconBeam(position: BlockPos, colour: Colour) {
//    val matrix = poseStackCompat() ?: return
//    val bufferSource = bufferSourceCompat() ?: return
//    val camera = camera()?.position ?: return
//
//    matrix.pushPose()
//    matrix.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z)
//    val length = camera.subtract(position.center).horizontalDistance().toFloat()
//    val scale = if (mc.player != null && mc.player?.isScoping == true) 1.0f else maxOf(1.0f, length / 96.0f)
//
//    BeaconRenderer.renderBeaconBeam(
//        matrix, bufferSource, BeaconRenderer.BEAM_LOCATION,
//        tickCounter().getGameTimeDeltaPartialTick(true), scale, world().gameTime, 0, 319, colour.rgba, 0.2f * scale, 0.25f * scale
//    )
//    matrix.popPose()
//}

fun WorldRenderContext.drawText(text: Component, pos: Vec3, colour: Colour = Colour.TRANSPARENT, shadow: Boolean = true, scale: Float = 0.5f, depth: Boolean = false) {
    val stack = poseStackCompat() ?: return

    stack.pushPose()
    val matrix = stack.last().pose()
    with(scale * 0.025f) {
        val cameraPos = -camera().pos
        matrix.translate(pos.toVector3f()).translate(cameraPos.x.toFloat() , cameraPos.y.toFloat(), cameraPos.z.toFloat()).rotate(camera().rotation()).scale(this, -this, this)
    }

    val consumers = MultiBufferSource.immediate(ALLOCATOR)

    mc.font?.let {
        // LightTexture was renamed to Lightmap in 26.x and FULL_BRIGHT no
        // longer lives there; the value is the stable packed lightmap
        // constant 0xF000F0 (full block + sky light).
        //? if >= 26 {
        /*val fullBright = 0xF000F0
        *///? } else {
        val fullBright = LightTexture.FULL_BRIGHT
        //? }
        it.drawInBatch(
            text, -it.width(text) / 2f, 0f, -1, shadow, matrix, consumers,
            if (depth) Font.DisplayMode.NORMAL else Font.DisplayMode.SEE_THROUGH,
            colour.rgb, fullBright
        )
    }

    consumers.endBatch()
    stack.popPose()
}

fun WorldRenderContext.drawCylinder(
    center: Vec3,
    radius: Float,
    height: Float,
    colour: Colour,
    segments: Int = 32,
    thickness: Float = 5f,
    depth: Boolean = false
) {
    val matrix = poseStackCompat() ?: return
    val bufferSource = bufferSourceCompat() ?: return
    val layer = if (depth) CustomRenderLayer.LINE_LIST else CustomRenderLayer.LINE_LIST_ESP
    val camera = camera()?.pos ?: return

    matrix.pushPose()
    matrix.translate(center.x - camera.x, center.y - camera.y, center.z - camera.z)
    val width = (thickness / camera.distanceToSqr(center).pow(0.15)).toFloat()
    setLineWidthIfSupported(width)

    val angleStep = 2.0 * Math.PI / segments
    val buffer = bufferSource.getBuffer(layer)
    val pose = matrix.last()

    for (i in 0 until segments) {
        val angle1 = i * angleStep
        val angle2 = (i + 1) * angleStep

        val x1 = (radius * kotlin.math.cos(angle1)).toFloat()
        val z1 = (radius * kotlin.math.sin(angle1)).toFloat()
        val x2 = (radius * kotlin.math.cos(angle2)).toFloat()
        val z2 = (radius * kotlin.math.sin(angle2)).toFloat()

        // top ring edge, bottom ring edge, vertical pillar
        pose.lineVertex(buffer, x1, height, z1, x2, height, z2, colour.rgb, width)
        pose.lineVertex(buffer, x1,     0f, z1, x2,     0f, z2, colour.rgb, width)
        pose.lineVertex(buffer, x1,     0f, z1, x1, height, z1, colour.rgb, width)
    }

    matrix.popPose()
    bufferSource.endBatch()
}