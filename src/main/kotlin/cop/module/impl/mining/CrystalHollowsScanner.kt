package cop.module.impl.mining

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import cop.CopMod.logger
import cop.api.colour.Colour
import cop.api.colour.withAlpha
import cop.api.events.RenderEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.events.core.EventBus
import cop.api.skyblock.Island
import cop.module.Module
import cop.module.impl.mining.CrystalHollowsMap.X_MAX
import cop.module.impl.mining.CrystalHollowsMap.X_MIN
import cop.module.impl.mining.CrystalHollowsMap.Z_MAX
import cop.module.impl.mining.CrystalHollowsMap.Z_MIN
import cop.module.impl.mining.CrystalHollowsMap.isDirty
import cop.module.impl.mining.enums.Structure
import cop.module.settings.UIComponent.Companion.visibleIf
import cop.utils.ChatUtils
import cop.utils.ChatUtils.literal
import cop.utils.EntityUtils.renderX
import cop.utils.EntityUtils.renderY
import cop.utils.EntityUtils.renderZ
import cop.utils.WorldUtils.registryName
import cop.utils.aabb
import cop.utils.render.drawFilledBox
import cop.utils.render.drawStyledBox
import cop.utils.render.drawText
import cop.utils.vec3
import java.util.ArrayDeque
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.sqrt

// https://github.com/RoseGoldIsntGay/GumTuneClient/blob/main/src/main/java/rosegold/gumtuneclient/modules/world/WorldScanner.java
object CrystalHollowsScanner : Module(
    "Crystal Hollows Scanner",
    area = Island.CrystalHollows
) {
    private val structureScanner by switch("Structure scanner")
    val routeScanner by switch("Route scanner")
    private val style by selector("Style", "Box", arrayListOf("Box", "Filled box"), desc = "Esp render style to be used.").visibleIf { routeScanner }
    private val distCols by switch("Distance colours").visibleIf { routeScanner }
    val colour by colourPicker("Colour", Colour.WHITE, allowAlpha = true).visibleIf { routeScanner && !distCols }
    private val fillDistCols by switch("Fill distance colours").visibleIf { style.selected == "Filled box" && routeScanner }
    private val fillColour by colourPicker("Fill colour", Colour.WHITE.withAlpha(0.33f), allowAlpha = true).visibleIf { style.selected == "Filled box" && routeScanner && !fillDistCols }
    private val thickness by slider("Thickness", 4f, 1f, 8f, 1f).visibleIf { routeScanner }

    private const val STRUCTURE_MAX_Y = 179
    private const val ROUTE_MAX_Y = 70
    private const val MIN_BLOCKS_PER_TICK = 256
    private const val SCAN_BUDGET_NANOS = 2_000_000L

    /**
     * Scanner state is confined to the Minecraft client thread. Keeping these
     * as ordinary collections is then both cheaper and safer than mixing a
     * concurrent map with non-thread-safe values.
     */
    val scannedChunks = HashSet<Long>()
    private val foundStructures = mutableMapOf<Structure, MutableList<BlockPos>>()
    val foundRouteBlocks = mutableListOf<BlockPos>()
    private val foundRouteBlockKeys = HashSet<Long>()
    private val pendingScans = ArrayDeque<ChunkScan>()
    private var scanWorld: ClientLevel? = null

    private data class ChunkScan(
        val world: ClientLevel,
        val chunk: LevelChunk,
        val key: Long,
        val scanStructures: Boolean,
        val scanRoutes: Boolean,
        val minX: Int,
        val maxX: Int,
        val minZ: Int,
        val maxZ: Int,
        val minY: Int,
        val maxY: Int,
        var x: Int = minX,
        var z: Int = minZ,
        var y: Int = minY,
    ) {
        fun advance(): Boolean {
            y++
            if (y <= maxY) return true
            y = minY

            z++
            if (z <= maxZ) return true
            z = minZ

            x++
            return x <= maxX
        }
    }

    init {
        on<WorldEvent.Chunk.Load> {
            if (!structureScanner && !routeScanner) return@on
            if (mc.isSameThread) enqueueChunk(chunk)
            else mc.execute { enqueueChunk(chunk) }
        }

        on<TickEvent.End> {
            processPendingScans()
        }

        on<RenderEvent.World> {
            if (structureScanner) foundStructures.forEach { (structure, positions) ->
                positions.forEach { blockPos ->
                    val pos = blockPos.vec3
                    val dist = pos.distanceToSqr(player.renderX, player.renderY, player.renderZ)
                    val scale = (0.5 + dist.pow(0.5) / 10.0).toFloat()
                    ctx.drawFilledBox(pos.aabb, Colour.WHITE)
                    ctx.drawText(literal(structure.displayName).withColor(structure.colour.rgb), pos, scale = scale)
                }
            }

            if (routeScanner) foundRouteBlocks.forEach { blockPos ->
                var currentDistCol: Colour? = null
                if (distCols || fillDistCols) {
                    val dist = sqrt(player.distanceToSqr(blockPos.x + 0.5, blockPos.y + 0.5, blockPos.z + 0.5))
                    val hue = (1f - (dist / 64f).coerceIn(0.0, 1.0)).toFloat() * 0.33f
                    currentDistCol = Colour.HSB(hue, 1f, 1f)
                }

                val c = if (distCols && currentDistCol != null) currentDistCol else colour
                val fc = if (fillDistCols && currentDistCol != null) currentDistCol.withAlpha(fillColour.alpha) else fillColour

                ctx.drawStyledBox(style.selected, blockPos.aabb, c, fc, thickness, false)
            }
        }

        // World cleanup must also run while the module is disabled. Module
        // listeners are unregistered on disable, so this lifecycle hook is
        // intentionally registered directly for the application's lifetime.
        EventBus.on<WorldEvent.Change> {
            if (mc.isSameThread) resetScanner(null)
            else mc.execute { resetScanner(null) }
        }
    }

    private fun enqueueChunk(chunk: LevelChunk) {
        val world = chunk.level as? ClientLevel ?: return
        if (scanWorld !== world) resetScanner(world)

        val chunkMinX = chunk.pos.minBlockX
        val chunkMinZ = chunk.pos.minBlockZ
        val minX = maxOf(0, X_MIN - chunkMinX)
        val maxX = minOf(15, X_MAX - chunkMinX)
        val minZ = maxOf(0, Z_MIN - chunkMinZ)
        val maxZ = minOf(15, Z_MAX - chunkMinZ)
        if (minX > maxX || minZ > maxZ) return

        val scanStructures = structureScanner
        val scanRoutes = routeScanner
        val minY = maxOf(if (scanStructures && !scanRoutes) 30 else 0, world.minY)
        val maxY = minOf(if (scanRoutes && !scanStructures) ROUTE_MAX_Y else STRUCTURE_MAX_Y, world.maxY - 1)
        if (minY > maxY) return

        // 26.x renamed ChunkPos.toLong() -> pack().
        val key =
            //? if >= 26 {
            /*chunk.pos.pack()*/
            //? } else {
            chunk.pos.toLong()
            //? }
        if (!scannedChunks.add(key)) return

        pendingScans.addLast(
            ChunkScan(
                world = world,
                chunk = chunk,
                key = key,
                scanStructures = scanStructures,
                scanRoutes = scanRoutes,
                minX = minX,
                maxX = maxX,
                minZ = minZ,
                maxZ = maxZ,
                minY = minY,
                maxY = maxY,
            )
        )
        isDirty = true
    }

    private fun processPendingScans() {
        val world = mc.level ?: return
        if (scanWorld !== world) {
            resetScanner(world)
            return
        }

        val deadline = System.nanoTime() + SCAN_BUDGET_NANOS
        var processed = 0
        var validatedScan: ChunkScan? = null
        while (pendingScans.isNotEmpty() && (processed < MIN_BLOCKS_PER_TICK || System.nanoTime() < deadline)) {
            val scan = pendingScans.peekFirst()
            if (validatedScan !== scan) {
                val loadedChunk = world.getChunk(
                    scan.chunk.pos.x,
                    scan.chunk.pos.z,
                    ChunkStatus.FULL,
                    false,
                )
                if (scan.world !== world || loadedChunk !== scan.chunk) {
                    pendingScans.removeFirst()
                    scannedChunks.remove(scan.key)
                    isDirty = true
                    continue
                }
                validatedScan = scan
            }

            try {
                scanBlock(scan)
            } catch (e: Exception) {
                logger.warn("Failed to scan Crystal Hollows chunk ${scan.chunk.pos}", e)
                pendingScans.removeFirst()
                scannedChunks.remove(scan.key)
                isDirty = true
                validatedScan = null
                continue
            }
            processed++
            if (!scan.advance()) pendingScans.removeFirst()
        }
    }

    private fun scanBlock(scan: ChunkScan) {
        val pos = BlockPos(scan.chunk.pos.minBlockX + scan.x, scan.y, scan.chunk.pos.minBlockZ + scan.z)

        if (scan.scanStructures) Structure.entries.forEach { structure ->
            if (!structure.quarter.test(pos)) return@forEach

            val existing = foundStructures[structure]
            if (structure.canBeMultiple) {
                if (existing?.any { it.isWithinChunks(pos, 4) } == true) return@forEach
            } else if (existing != null) {
                return@forEach
            }

            if (scanStructure(scan, structure)) {
                val realPos = pos.offset(structure.xOffset, structure.yOffset, structure.zOffset)
                foundStructures.getOrPut(structure) { mutableListOf() }.add(realPos)
                ChatUtils.modMessage("Found ${structure.displayName} at ${pos.x}, ${pos.y}, ${pos.z}")
            }
        }

        if (scan.scanRoutes && scan.y <= ROUTE_MAX_Y) {
            if (scan.chunk.getBlockState(pos).block != Blocks.COBBLESTONE) return
            val valid = Direction.entries.asSequence().filter { it != Direction.DOWN }.all {
                val state = scan.world.getBlockState(pos.relative(it))
                state.isAir || state.block.registryName.contains("glass")
            }

            if (valid && foundRouteBlockKeys.add(pos.asLong())) {
                foundRouteBlocks.add(pos)
            }
        }
    }

    private fun scanStructure(scan: ChunkScan, structure: Structure): Boolean {
        val lastY = scan.y + structure.blocks.lastIndex
        if (lastY > minOf(STRUCTURE_MAX_Y, scan.world.maxY - 1)) return false

        structure.blocks.forEachIndexed { i, block ->
            if (block == null) return@forEachIndexed

            val pos = BlockPos(
                scan.chunk.pos.minBlockX + scan.x,
                scan.y + i,
                scan.chunk.pos.minBlockZ + scan.z,
            )
            val state = scan.chunk.getBlockState(pos)

            if (state.block != block) {
                return false
            }
        }
        return true
    }

    private fun resetScanner(world: ClientLevel?) {
        scanWorld = world
        pendingScans.clear()
        scannedChunks.clear()
        foundStructures.clear()
        foundRouteBlocks.clear()
        foundRouteBlockKeys.clear()
        isDirty = true
    }

    private fun BlockPos.isWithinChunks(other: BlockPos, chunks: Int): Boolean {
        val dx = (this.x shr 4) - (other.x shr 4)
        val dz = (this.z shr 4) - (other.z shr 4)
        return dx.absoluteValue <= chunks && dz.absoluteValue <= chunks
    }
}
