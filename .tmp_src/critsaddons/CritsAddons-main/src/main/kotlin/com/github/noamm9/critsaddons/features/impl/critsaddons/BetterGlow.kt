package com.github.noamm9.critsaddons.features.impl.critsaddons

import com.github.noamm9.event.EventPriority
import com.github.noamm9.event.impl.CheckEntityGlowEvent
import com.github.noamm9.event.impl.EntityDeathEvent
import com.github.noamm9.event.impl.MainThreadPacketReceivedEvent
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
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.model.Model
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.ambient.Bat
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.monster.EnderMan
import net.minecraft.world.entity.player.Player
import org.lwjgl.system.MemoryStack
import java.awt.Color
import java.util.WeakHashMap
import java.util.function.Supplier

object BetterGlow : Feature(
    name = "Better Glow",
    description = "Custom framebuffer bloom glow for starred dungeon mobs."
) {
    private val targetSection = "targets"
    private val colorSection = "colors"

    private val highlightBats by ToggleSetting("Highlight Bats", true)
        .section(targetSection)
        .withDescription("Highlights dungeon bats like Star Mob ESP.")

    private val highlightFels by ToggleSetting("Highlight Fels", false)
        .section(targetSection)
        .withDescription("Highlights Fels like Star Mob ESP.")

    private val outlineColor by ColorSetting("Outline Color", Color(255, 230, 0, 255), true)
        .section(colorSection)
        .withDescription("Outline and bloom color. Alpha controls glow opacity.")

    private val framebufferBloom by ToggleSetting("Framebuffer Bloom", true)
        .section(colorSection)
        .withDescription("Uses Minecraft's entity outline framebuffer and a wider blur pass.")

    private val bloomBlurRadius by SliderSetting("Blur Radius", 1.0, 0.0, 15.0, 0.5)
        .section(colorSection)
        .withDescription("Framebuffer bloom blur radius in screen pixels. 1 is a tight one-pixel fade.")

    private val starMobs = HashSet<Int>()
    private val checked = HashSet<Int>()
    private val chamStates = WeakHashMap<LivingEntityRenderState, ChamStyle>()
    private const val BLOOM_BLUR_SHADER = "critsaddons:post/better_glow_box_blur"

    private var cachedBlurRadius = Float.NaN
    private var cachedOutlineAlpha = -1
    private var horizontalBlurUniform: GpuBuffer? = null
    private var verticalBlurUniform: GpuBuffer? = null

    override fun init() {
        register<MainThreadPacketReceivedEvent.Post> {
            if (!enabled || !LocationUtils.inDungeon || inBoss) return@register
            val packet = event.packet as? ClientboundSetEntityDataPacket ?: return@register

            val entity = mc.level?.getEntity(packet.id()) ?: return@register
            when (entity) {
                is ArmorStand -> {
                    val formattedName = entity.customName?.formattedText ?: return@register
                    val cleanName = formattedName.removeFormatting()
                    if (cleanName.endsWith("\u2764") && cleanName.contains("\u272f")) {
                        checkStarMob(entity, formattedName)
                    }
                }

                is Player -> {
                    val name = mc.connection?.getPlayerInfo(entity.uuid)?.profile?.name ?: return@register
                    if (name.equalsOneOf("Shadow Assassin", "Lost Adventurer", "Diamond Guy", "King Midas")) {
                        starMobs.add(entity.id)
                    }
                }
            }
        }

        register<EntityDeathEvent> {
            starMobs.remove(event.entity.id)
            checked.remove(event.entity.id)
        }

        register<WorldChangeEvent> {
            clear()
        }

        register<CheckEntityGlowEvent>(EventPriority.LOWEST) {
            if (!enabled || !LocationUtils.inDungeon || inBoss) return@register
            if (isBetterGlowTarget(event.entity)) {
                event.shouldGlow = false
            }
        }
    }

    private fun isBetterGlowTarget(entity: Entity): Boolean {
        if (entity.id in starMobs) return true
        return getSpecialTarget(entity)
    }

    @JvmStatic
    fun captureRenderState(entity: Entity, state: LivingEntityRenderState) {
        if (!enabled || !LocationUtils.inDungeon || inBoss || entity.isRemoved || entity === mc.player || !isBetterGlowTarget(entity)) {
            chamStates.remove(state)
            return
        }

        val style = ChamStyle(
            outline = outlineColor.value,
            framebufferBloom = framebufferBloom.value
        )

        chamStates[state] = style
        if (style.framebufferBloom && style.outline.alpha > 0) {
            state.outlineColor = toArgb(style.outline)
        }
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun submitChams(
        state: LivingEntityRenderState,
        model: Model<*>,
        texture: ResourceLocation,
        modelTint: Int,
        poseStack: PoseStack,
        collector: SubmitNodeCollector
    ) {
        val style = chamStates[state] ?: return
        if (style.framebufferBloom || style.outline.alpha <= 0) return

        val typedModel = model as Model<LivingEntityRenderState>

        poseStack.pushPose()
        poseStack.scale(1.035f, 1.035f, 1.035f)
        collector.submitModel(
            typedModel,
            state,
            poseStack,
            RenderType.entityTranslucentEmissive(texture, false),
            LightTexture.FULL_BRIGHT,
            OverlayTexture.NO_OVERLAY,
            multiplyArgb(modelTint, style.outline),
            null,
            LivingEntityRenderState.NO_OUTLINE,
            null
        )
        poseStack.popPose()
    }

    @JvmStatic
    fun getBloomBlurConfig(
        shaderName: String,
        outputTargetPath: String,
        uniformName: String,
        original: GpuBuffer
    ): GpuBuffer {
        if (shaderName != BLOOM_BLUR_SHADER || uniformName != "BlurConfig" || !enabled || !framebufferBloom.value) {
            return original
        }

        val radius = bloomBlurRadius.value.toFloat().coerceIn(0f, 15f)
        val alpha = outlineColor.value.alpha.coerceIn(0, 255)
        if (
            radius != cachedBlurRadius ||
            alpha != cachedOutlineAlpha ||
            horizontalBlurUniform?.isClosed != false ||
            verticalBlurUniform?.isClosed != false
        ) {
            rebuildBlurUniforms(radius, alpha)
        }

        return if (outputTargetPath == "swap") {
            horizontalBlurUniform ?: original
        }
        else {
            verticalBlurUniform ?: original
        }
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
        if (!checked.add(armorStand.id)) return

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
        checked.clear()
        chamStates.clear()
    }

    private fun rebuildBlurUniforms(radius: Float, alpha: Int) {
        horizontalBlurUniform?.safeClose()
        verticalBlurUniform?.safeClose()
        cachedBlurRadius = radius
        cachedOutlineAlpha = alpha
        val opacity = alpha / 255f
        horizontalBlurUniform = createBlurUniform(1f, 0f, radius, opacity)
        verticalBlurUniform = createBlurUniform(0f, 1f, radius, opacity)
    }

    private fun createBlurUniform(x: Float, y: Float, radius: Float, opacity: Float): GpuBuffer {
        val size = Std140SizeCalculator().putVec2().putFloat().putFloat().get()
        MemoryStack.stackPush().use { stack ->
            val builder = Std140Builder.onStack(stack, size)
                .putVec2(x, y)
                .putFloat(radius)
                .putFloat(opacity)

            return RenderSystem.getDevice().createBuffer(
                Supplier { "CritsAddons Better Glow BlurConfig $x,$y,$radius,$opacity" },
                GpuBuffer.USAGE_UNIFORM,
                builder.get()
            )
        }
    }

    private fun GpuBuffer.safeClose() {
        runCatching {
            if (!isClosed) close()
        }
    }

    private data class ChamStyle(
        val outline: Color,
        val framebufferBloom: Boolean
    )

    private fun toArgb(color: Color): Int {
        return (color.alpha shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
    }

    private fun multiplyArgb(modelTint: Int, color: Color): Int {
        val modelA = (modelTint ushr 24) and 0xFF
        val modelR = (modelTint ushr 16) and 0xFF
        val modelG = (modelTint ushr 8) and 0xFF
        val modelB = modelTint and 0xFF

        val a = color.alpha * modelA / 255
        val r = color.red * modelR / 255
        val g = color.green * modelG / 255
        val b = color.blue * modelB / 255

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}
