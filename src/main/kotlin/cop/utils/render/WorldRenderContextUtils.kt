package cop.utils.render

import cop.CopMod.mc
import cop.api.colour.*
import cop.mixins.accessors.CameraAccessor
import cop.utils.EntityUtils.renderPos
import cop.utils.unaryMinus
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * World-render helpers. Lines and wireframes are emitted as camera-facing
 * quads (see [billboardLineQuad]) rather than GL line primitives — Iris/Sodium
 * shader packs drop line primitives, so the previous GL_LINES path was
 * invisible under shaders. Filled boxes still use the existing triangle-strip
 * path which is already shader-friendly.
 *
 * Inspired by yourboykyle's Secret Routes Mod (GPL-3), `AnotherRenderingUtil`.
 */
private val ALLOCATOR = ByteBufferBuilder(1536)

private fun camera() = mc.gameRenderer.mainCamera

// `Camera.position` is a public field in 1.21.10 but became private in 1.21.11.
// The mixin accessor exposes it through one signature on both versions.
private val net.minecraft.client.Camera.pos: Vec3
    get() = (this as CameraAccessor).position

/**
 * Pixel-thickness → world-thickness factor. The public `thickness` argument is
 * pixel-style (matching the old GL_LINES path) and gets multiplied by this
 * constant to get a world-space line width. Tuned to roughly match the
 * previous visual weight at typical viewing distances.
 */
private const val THICKNESS_MULTIPLIER = 0.01f

/** Minimum world-space half-width so degenerate-thin lines remain visible. */
private const val MIN_HALF_WIDTH = 0.001f

/**
 * Emit one camera-facing quad for a line segment. Vertices are in world
 * coordinates — caller's PoseStack must already be translated by `-camera.pos`.
 *
 * The quad's width direction is `lineDir × (start - camera)`, which is
 * perpendicular to both the line and the viewing direction so the quad always
 * faces the camera.
 */
private fun PoseStack.Pose.billboardLineQuad(
    buffer: VertexConsumer,
    sx: Float, sy: Float, sz: Float,
    ex: Float, ey: Float, ez: Float,
    cx: Float, cy: Float, cz: Float,
    colour: Int,
    halfWidth: Float,
) {
    val lineDx = ex - sx
    val lineDy = ey - sy
    val lineDz = ez - sz
    val lineLenSq = lineDx * lineDx + lineDy * lineDy + lineDz * lineDz
    if (lineLenSq < 1e-10f) return
    val invLineLen = 1f / sqrt(lineLenSq)
    val lnx = lineDx * invLineLen
    val lny = lineDy * invLineLen
    val lnz = lineDz * invLineLen

    var camDx = sx - cx
    var camDy = sy - cy
    var camDz = sz - cz
    val camLenSq = camDx * camDx + camDy * camDy + camDz * camDz
    if (camLenSq < 1e-6f) {
        camDx = 0f; camDy = 1f; camDz = 0f
    } else {
        val invCamLen = 1f / sqrt(camLenSq)
        camDx *= invCamLen; camDy *= invCamLen; camDz *= invCamLen
    }

    var wx = lny * camDz - lnz * camDy
    var wy = lnz * camDx - lnx * camDz
    var wz = lnx * camDy - lny * camDx
    val wLenSq = wx * wx + wy * wy + wz * wz
    if (wLenSq < 1e-6f) {
        // Line aimed straight at/away from the camera: pick any perpendicular.
        if (abs(lnx) > 0.9f) { wx = 0f; wy = 1f; wz = 0f }
        else { wx = 1f; wy = 0f; wz = 0f }
    } else {
        val invWLen = 1f / sqrt(wLenSq)
        wx *= invWLen; wy *= invWLen; wz *= invWLen
    }
    wx *= halfWidth; wy *= halfWidth; wz *= halfWidth

    buffer.addVertex(this, sx - wx, sy - wy, sz - wz).setColor(colour)
    buffer.addVertex(this, sx + wx, sy + wy, sz + wz).setColor(colour)
    buffer.addVertex(this, ex + wx, ey + wy, ez + wz).setColor(colour)
    buffer.addVertex(this, ex - wx, ey - wy, ez - wz).setColor(colour)
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

private fun halfWidthOf(thickness: Float): Float =
    max(MIN_HALF_WIDTH, thickness * THICKNESS_MULTIPLIER * 0.5f)

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
    val layer = if (depth) CustomRenderLayer.BILLBOARD_LINE_QUAD else CustomRenderLayer.BILLBOARD_LINE_QUAD_ESP
    val cam = camera().pos
    val halfWidth = halfWidthOf(thickness)

    matrix.pushPose()
    matrix.translate(-cam.x, -cam.y, -cam.z)

    val pose = matrix.last()
    val buffer = bufferSource.getBuffer(layer)
    val cx = cam.x.toFloat(); val cy = cam.y.toFloat(); val cz = cam.z.toFloat()
    val rgb = colour.rgb
    val pointList = points.toList()
    for (i in 0 until pointList.size - 1) {
        val s = pointList[i]; val e = pointList[i + 1]
        pose.billboardLineQuad(
            buffer,
            s.x.toFloat(), s.y.toFloat(), s.z.toFloat(),
            e.x.toFloat(), e.y.toFloat(), e.z.toFloat(),
            cx, cy, cz, rgb, halfWidth,
        )
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
    val layer = if (depth) CustomRenderLayer.BILLBOARD_LINE_QUAD else CustomRenderLayer.BILLBOARD_LINE_QUAD_ESP
    val cam = camera().pos
    val halfWidth = halfWidthOf(thickness)

    matrix.pushPose()
    matrix.translate(-cam.x, -cam.y, -cam.z)

    val pose = matrix.last()
    val buffer = bufferSource.getBuffer(layer)
    val cx = cam.x.toFloat(); val cy = cam.y.toFloat(); val cz = cam.z.toFloat()
    val x1 = aabb.minX.toFloat(); val y1 = aabb.minY.toFloat(); val z1 = aabb.minZ.toFloat()
    val x2 = aabb.maxX.toFloat(); val y2 = aabb.maxY.toFloat(); val z2 = aabb.maxZ.toFloat()
    val rgb = colour.rgb

    // bottom rectangle
    pose.billboardLineQuad(buffer, x1, y1, z1, x2, y1, z1, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x2, y1, z1, x2, y1, z2, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x2, y1, z2, x1, y1, z2, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x1, y1, z2, x1, y1, z1, cx, cy, cz, rgb, halfWidth)
    // top rectangle
    pose.billboardLineQuad(buffer, x1, y2, z1, x2, y2, z1, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x2, y2, z1, x2, y2, z2, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x2, y2, z2, x1, y2, z2, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x1, y2, z2, x1, y2, z1, cx, cy, cz, rgb, halfWidth)
    // pillars
    pose.billboardLineQuad(buffer, x1, y1, z1, x1, y2, z1, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x2, y1, z1, x2, y2, z1, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x2, y1, z2, x2, y2, z2, cx, cy, cz, rgb, halfWidth)
    pose.billboardLineQuad(buffer, x1, y1, z2, x1, y2, z2, cx, cy, cz, rgb, halfWidth)

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
    val layer = if (depth) CustomRenderLayer.BILLBOARD_LINE_QUAD else CustomRenderLayer.BILLBOARD_LINE_QUAD_ESP
    val cam = camera().pos
    val halfWidth = halfWidthOf(thickness)

    matrix.pushPose()
    matrix.translate(-cam.x, -cam.y, -cam.z)

    val pose = matrix.last()
    val buffer = bufferSource.getBuffer(layer)
    val cx = cam.x.toFloat(); val cy = cam.y.toFloat(); val cz = cam.z.toFloat()
    val centerX = center.x.toFloat()
    val centerY = center.y.toFloat()
    val centerZ = center.z.toFloat()
    val topY = centerY + height
    val rgb = colour.rgb

    val angleStep = 2.0 * Math.PI / segments
    for (i in 0 until segments) {
        val a1 = i * angleStep
        val a2 = (i + 1) * angleStep
        val x1 = centerX + (radius * kotlin.math.cos(a1)).toFloat()
        val z1 = centerZ + (radius * kotlin.math.sin(a1)).toFloat()
        val x2 = centerX + (radius * kotlin.math.cos(a2)).toFloat()
        val z2 = centerZ + (radius * kotlin.math.sin(a2)).toFloat()

        // top ring edge, bottom ring edge, vertical pillar
        pose.billboardLineQuad(buffer, x1,    topY, z1, x2,    topY, z2, cx, cy, cz, rgb, halfWidth)
        pose.billboardLineQuad(buffer, x1, centerY, z1, x2, centerY, z2, cx, cy, cz, rgb, halfWidth)
        pose.billboardLineQuad(buffer, x1, centerY, z1, x1,    topY, z1, cx, cy, cz, rgb, halfWidth)
    }

    matrix.popPose()
    bufferSource.endBatch(layer)
}