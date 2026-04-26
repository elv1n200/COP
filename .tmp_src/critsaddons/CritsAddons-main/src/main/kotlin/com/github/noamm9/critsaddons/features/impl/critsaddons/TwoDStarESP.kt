package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DepthTestFunction
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import com.github.noamm9.event.impl.EntityDeathEvent
import com.github.noamm9.event.impl.RenderWorldEvent
import com.github.noamm9.event.impl.WorldChangeEvent
import com.github.noamm9.features.Feature
import com.github.noamm9.ui.clickgui.components.getValue
import com.github.noamm9.ui.clickgui.components.impl.ColorSetting
import com.github.noamm9.ui.clickgui.components.impl.SliderSetting
import com.github.noamm9.ui.clickgui.components.impl.ToggleSetting
import com.github.noamm9.ui.clickgui.components.provideDelegate
import com.github.noamm9.ui.clickgui.components.section
import com.github.noamm9.ui.clickgui.components.withDescription
import com.github.noamm9.utils.ChatUtils.formattedText
import com.github.noamm9.utils.ChatUtils.removeFormatting
import com.github.noamm9.utils.ChatUtils.unformattedText
import com.github.noamm9.utils.Utils.equalsOneOf
import com.github.noamm9.utils.location.LocationUtils
import com.github.noamm9.utils.location.LocationUtils.dungeonFloorNumber
import com.github.noamm9.utils.location.LocationUtils.inBoss
import com.github.noamm9.utils.render.Render3D
import com.github.noamm9.utils.render.RenderContext
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.awt.Color
import kotlin.math.max

object TwoDStarESP : Feature(
    name = "2D Star ESP",
    description = "Draws a flat 2D box around starred dungeon mobs based on the direction they are facing."
) {
    private val renderSection = "render"
    private val targetSection = "targets"

    private val boxColor by ColorSetting("Border Color", Color(255, 230, 0, 220), true)
        .section(renderSection)
    private val fillColor by ColorSetting("Fill Color", Color(255, 230, 0, 45), true)
    private val lineWidth by SliderSetting("Line Width", 2.0f, 0.5f, 8.0f, 0.5f)
    private val glowingBorder by ToggleSetting("Glowing Border", false)
        .withDescription("Draws translucent expanded border passes around the main 2D box.")
    private val glowRadius by SliderSetting("Glow Radius", 0.18f, 0.05f, 0.75f, 0.01f)
        .withDescription("World-space glow expansion around the border.")
    private val widthScale by SliderSetting("Width Scale", 1.15f, 0.5f, 3.0f, 0.05f)
    private val heightScale by SliderSetting("Height Scale", 1.05f, 0.5f, 3.0f, 0.05f)
    private val rotateWithMob by ToggleSetting("Rotate With Mob", true)
        .withDescription("On = box is perpendicular to the mob's facing direction. Off = box always faces your camera.")
    private val renderThroughWalls by ToggleSetting("Render Through Walls", true)
    private val maxDistance by SliderSetting("Max Distance", 80, 10, 200, 5)
        .withDescription("Maximum distance in blocks to draw ESP boxes.")

    private val highlightBats by ToggleSetting("Highlight Bats", true)
        .section(targetSection)
        .withDescription("Highlights dungeon bats like Star Mob ESP.")
    private val highlightFels by ToggleSetting("Highlight Fels", false)
        .section(targetSection)
        .withDescription("Highlights Fels like Star Mob ESP.")

    private val starMobs = HashSet<Int>()
    private val checkedArmorStands = HashSet<Int>()
    private val filledQuadsThroughWallsPipeline by lazy {
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                .withLocation(ResourceLocation.fromNamespaceAndPath("critsaddons", "2d_star_esp_filled_quads_through_walls"))
                .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withCull(false)
                .build()
        )
    }
    private val filledQuadsThroughWallsLayer by lazy {
        RenderType.create(
            "critsaddons_2d_star_esp_filled_quads_through_walls",
            1536,
            false,
            true,
            filledQuadsThroughWallsPipeline,
            RenderType.CompositeState.builder()
                .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                .createCompositeState(false)
        )
    }

    override fun init() {
        register<RenderWorldEvent> {
            if (!enabled || !LocationUtils.inDungeon || inBoss) return@register

            val level = mc.level ?: return@register
            val player = mc.player ?: return@register

            discoverStarMobs()
            val maxDistanceSqr = maxDistance.value.toDouble().let { it * it }

            level.entitiesForRendering().forEach { entity ->
                if (entity === player || entity is ArmorStand || entity.isRemoved) return@forEach
                if (entity.distanceToSqr(player) > maxDistanceSqr) return@forEach
                if (!isStarEspTarget(entity)) return@forEach

                renderFacingBox(event, entity)
            }
        }

        register<EntityDeathEvent> {
            starMobs.remove(event.entity.id)
            checkedArmorStands.remove(event.entity.id)
        }

        register<WorldChangeEvent> {
            clear()
        }
    }

    private fun discoverStarMobs() {
        val level = mc.level ?: return
        level.entitiesForRendering()
            .filterIsInstance<ArmorStand>()
            .forEach { armorStand ->
                val formattedName = armorStand.customName?.formattedText ?: return@forEach
                val cleanName = formattedName.removeFormatting()
                if (cleanName.endsWith("\u2764") && cleanName.contains("\u272f")) {
                    checkStarMob(armorStand, formattedName)
                }
            }
    }

    private fun isStarEspTarget(entity: Entity): Boolean {
        if (entity.id in starMobs) return true
        return getSpecialTarget(entity)
    }

    private fun renderFacingBox(event: RenderWorldEvent, entity: Entity) {
        val box = entity.boundingBox
        val center = box.center
        val width = max(box.maxX - box.minX, box.maxZ - box.minZ)
            .coerceAtLeast(0.6) * widthScale.value.toDouble()
        val height = (box.maxY - box.minY).coerceAtLeast(0.8) * heightScale.value.toDouble()

        val forward = getBoxForward(event, entity, center)
        val right = Vec3(-forward.z, 0.0, forward.x)
        val halfWidth = width / 2.0
        val halfHeight = height / 2.0

        val topCenter = center.add(0.0, halfHeight, 0.0)
        val bottomCenter = center.add(0.0, -halfHeight, 0.0)
        val left = right.scale(-halfWidth)
        val rightOffset = right.scale(halfWidth)

        val topLeft = topCenter.add(left)
        val topRight = topCenter.add(rightOffset)
        val bottomLeft = bottomCenter.add(left)
        val bottomRight = bottomCenter.add(rightOffset)

        val fill = fillColor.value
        val throughWalls = renderThroughWalls.value
        if (fill.alpha > 0) {
            renderFilledQuad(event.ctx, topLeft, topRight, bottomRight, bottomLeft, fill, throughWalls)
        }

        val border = boxColor.value
        if (glowingBorder.value && border.alpha > 0) {
            renderGlowBorder(event, center, right, halfWidth, halfHeight, border, throughWalls)
        }

        renderBorder(event, topLeft, topRight, bottomRight, bottomLeft, border, lineWidth.value, throughWalls)
    }

    private fun getBoxForward(event: RenderWorldEvent, entity: Entity, center: Vec3): Vec3 {
        val forward = if (rotateWithMob.value) {
            Vec3.directionFromRotation(0f, entity.yRot)
        } else {
            event.ctx.camera.position.subtract(center)
        }

        val horizontal = Vec3(forward.x, 0.0, forward.z)
        return if (horizontal.lengthSqr() < 1.0E-6) {
            Vec3(0.0, 0.0, 1.0)
        } else {
            horizontal.normalize()
        }
    }

    private fun renderGlowBorder(
        event: RenderWorldEvent,
        center: Vec3,
        right: Vec3,
        halfWidth: Double,
        halfHeight: Double,
        color: Color,
        throughWalls: Boolean
    ) {
        val layers = 3
        for (layer in layers downTo 1) {
            val progress = layer.toDouble() / layers.toDouble()
            val expansion = glowRadius.value.toDouble() * progress
            val alpha = (color.alpha * 0.1 * layer).toInt().coerceIn(0, color.alpha / 2)
            if (alpha <= 0) continue

            val glowColor = Color(color.red, color.green, color.blue, alpha)
            val w = halfWidth + expansion
            val h = halfHeight + expansion
            val topCenter = center.add(0.0, h, 0.0)
            val bottomCenter = center.add(0.0, -h, 0.0)
            val left = right.scale(-w)
            val rightOffset = right.scale(w)

            renderBorder(
                event,
                topCenter.add(left),
                topCenter.add(rightOffset),
                bottomCenter.add(rightOffset),
                bottomCenter.add(left),
                glowColor,
                lineWidth.value + (layer * 0.75f),
                throughWalls
            )
        }
    }

    private fun renderBorder(
        event: RenderWorldEvent,
        topLeft: Vec3,
        topRight: Vec3,
        bottomRight: Vec3,
        bottomLeft: Vec3,
        color: Color,
        thickness: Float,
        throughWalls: Boolean
    ) {
        if (color.alpha <= 0) return

        Render3D.renderLine(event.ctx, topLeft, topRight, color, thickness, throughWalls)
        Render3D.renderLine(event.ctx, topRight, bottomRight, color, thickness, throughWalls)
        Render3D.renderLine(event.ctx, bottomRight, bottomLeft, color, thickness, throughWalls)
        Render3D.renderLine(event.ctx, bottomLeft, topLeft, color, thickness, throughWalls)
    }

    private fun renderFilledQuad(
        ctx: RenderContext,
        topLeft: Vec3,
        topRight: Vec3,
        bottomRight: Vec3,
        bottomLeft: Vec3,
        color: Color,
        throughWalls: Boolean
    ) {
        val matrixStack = ctx.matrixStack
        val camera = ctx.camera.position
        matrixStack.pushPose()
        matrixStack.translate(-camera.x, -camera.y, -camera.z)

        val layer = if (throughWalls) filledQuadsThroughWallsLayer else RenderType.debugQuads()
        val consumer = ctx.consumers.getBuffer(layer)
        val pose = matrixStack.last()
        val normal = topRight.subtract(topLeft).cross(bottomLeft.subtract(topLeft)).normalize()
        val normalVec = Vector3f(normal.x.toFloat(), normal.y.toFloat(), normal.z.toFloat())

        addQuadVertex(consumer, pose, topLeft, color, normalVec)
        addQuadVertex(consumer, pose, bottomLeft, color, normalVec)
        addQuadVertex(consumer, pose, bottomRight, color, normalVec)
        addQuadVertex(consumer, pose, topRight, color, normalVec)

        matrixStack.popPose()
    }

    private fun addQuadVertex(
        consumer: com.mojang.blaze3d.vertex.VertexConsumer,
        pose: com.mojang.blaze3d.vertex.PoseStack.Pose,
        pos: Vec3,
        color: Color,
        normal: Vector3f
    ) {
        consumer.addVertex(pose, pos.x.toFloat(), pos.y.toFloat(), pos.z.toFloat())
            .setColor(color.red, color.green, color.blue, color.alpha)
            .setNormal(pose, normal)
    }

    private fun getSpecialTarget(entity: Entity): Boolean {
        if (entity is Bat) {
            return highlightBats.value && !entity.isInvisible && !entity.isPassenger
        }

        if (entity is EnderMan) {
            return highlightFels.value && entity.name.unformattedText == "Dinnerbone"
        }

        if (entity is Player) {
            val name = entity.name.unformattedText.takeUnless { it.isBlank() } ?: return false
            if (name.contains("Shadow Assassin")) return true

            if (dungeonFloorNumber != 4 && !inBoss) {
                val bootsName = entity.getItemBySlot(EquipmentSlot.FEET)
                    .takeUnless { it.isEmpty }
                    ?.hoverName
                    ?.unformattedText
                    ?: return false

                return when (name) {
                    "Lost Adventurer" -> true
                    "Diamond Guy" -> "Perfect Boots" in bootsName
                    else -> false
                }
            }
        }

        return false
    }

    private fun checkStarMob(armorStand: Entity, name: String) {
        if (!checkedArmorStands.add(armorStand.id)) return

        val cleanName = name.removeFormatting().uppercase()
        val offset = if (cleanName.contains("WITHERMANCER")) 3 else 1
        val id = armorStand.id - offset
        val mob = armorStand.level().getEntity(id)

        if (mob !is ArmorStand && id !in starMobs && mob != null) {
            starMobs.add(id)
            return
        }

        val possibleEntities = armorStand.level().getEntities(
            armorStand,
            armorStand.boundingBox.move(0.0, -1.0, 0.0)
        ) { it !is ArmorStand }

        possibleEntities.find {
            it.id !in starMobs && when (it) {
                is Player -> !it.isInvisible && it.uuid.version() == 2 && it != mc.player
                is WitherBoss -> false
                else -> true
            }
        }?.let {
            if (!getSpecialTarget(it)) starMobs.add(it.id)
        }
    }

    private fun clear() {
        starMobs.clear()
        checkedArmorStands.clear()
    }
}
