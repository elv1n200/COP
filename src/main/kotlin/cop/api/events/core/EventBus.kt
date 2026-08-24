package cop.api.events.core

import cop.CopMod.mc
import cop.api.events.ChatEvent
import cop.api.events.DungeonEvent
import cop.api.events.GameEvent
import cop.api.events.GuiEvent
import cop.api.events.PacketEvent
import cop.api.events.RenderEvent
import cop.api.events.ServerEvent
import cop.api.events.TickEvent
import cop.api.events.WorldEvent
import cop.api.skyblock.dungeon.Dungeon
import cop.api.skyblock.dungeon.Dungeon.dungeonItemDrops
import cop.utils.StringUtils.containsOneOf
import cop.utils.equalsOneOf
import cop.utils.render.renderCopWorld
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
import net.minecraft.resources.ResourceLocation
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.item.ItemEntity
import cop.CopMod
import cop.utils.ui.rendering.NVGSpecialRenderer
import java.util.concurrent.ConcurrentHashMap

/**
 * COP's Fabric-to-module event bridge. Early project history mentioned an
 * unavailable "Zen" reference; the current lifecycle wiring, cached dispatch,
 * failure isolation and listener ownership are COP-specific implementations.
 */
object EventBus { // todo cleanup
    val events = ConcurrentHashMap<Class<*>, EventHandlers>()
    data class PrioritisedCallback<T>(val priority: Int, val callback: T.() -> Unit)
    private val reportedFailures = ConcurrentHashMap.newKeySet<PrioritisedCallback<*>>()
    var totalTicks = 0
        private set

    /**
     * Cached sorted snapshot of an event's subscribers. The previous code did
     * `handlers.sortedByDescending { it.priority }` *per dispatch* — one List
     * allocation per tick/frame/packet, with no module in the project actually
     * using a non-zero priority. Caching the sorted list moves the work to the
     * (rare) add/remove path and turns dispatch into a single volatile read.
     *
     * @author elvin
     */
    class EventHandlers {
        private val live = LinkedHashSet<PrioritisedCallback<*>>()
        @Volatile private var cached: List<PrioritisedCallback<*>> = emptyList()

        @Synchronized
        fun add(c: PrioritisedCallback<*>): Boolean {
            val added = live.add(c)
            if (added) refresh()
            return added
        }

        @Synchronized
        fun remove(c: PrioritisedCallback<*>): Boolean {
            val removed = live.remove(c)
            if (removed) refresh()
            return removed
        }

        private fun refresh() {
            cached = live.toList().sortedByDescending { it.priority }
        }

        fun snapshot(): List<PrioritisedCallback<*>> = cached
        fun isEmpty(): Boolean = cached.isEmpty()
    }

    private var lastWorld: ClientLevel? = null
    private var tabLoaded = false

    init {
        ClientTickEvents.START_CLIENT_TICK.register {
            val world = mc.level
            if (world != lastWorld) {
                if (world != null) WorldEvent.Load.Start().post()
                lastWorld = world
            }

            if (!tabLoaded && !mc.connection?.listedOnlinePlayers.isNullOrEmpty()) {
                tabLoaded = true
                WorldEvent.Load.End().post()
            }
            if (mc.level != null && mc.player != null) TickEvent.Start().post()
        }
        ClientTickEvents.END_CLIENT_TICK.register { if (mc.level != null && mc.player != null) TickEvent.End().post() }

        WorldRenderEvents.END_MAIN.register { context ->
            if (mc.level != null && mc.player != null) {
                context.renderCopWorld { RenderEvent.World(context).post() }
            }
        }

        ClientLifecycleEvents.CLIENT_STARTED.register { GameEvent.Load().post() }
        ClientLifecycleEvents.CLIENT_STOPPING.register { GameEvent.Unload().post() }

        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register { _, _ ->
            WorldEvent.Change().post()
            totalTicks = 0
            tabLoaded = false
            lastWorld = null
        }

        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            if (mc.level != null && mc.player != null) WorldEvent.Chunk.Load(chunk).post()
        }

        ClientPlayConnectionEvents.JOIN.register { handler, _, _ ->
            ServerEvent.Connect(handler.serverData?.ip ?: "SinglePlayer").post()
        }

        ClientPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            ServerEvent.Disconnect(handler.serverData?.ip ?: "SinglePlayer").post()
        }

        ScreenEvents.BEFORE_INIT.register { _, screen, _, _ ->
            ScreenMouseEvents.allowMouseClick(screen).register { _, click ->
                val event = GuiEvent.Click(screen, click.x, click.y, click.button(), true)
                event.post()
                !event.isCancelled()
            }

            ScreenMouseEvents.allowMouseRelease(screen).register { _, click ->
                val event = GuiEvent.Click(screen, click.x, click.y, click.button(), false)
                event.post()
                !event.isCancelled()
            }
        }

        HudElementRegistry.attachElementBefore(VanillaHudElements.SLEEP, ResourceLocation.fromNamespaceAndPath(CopMod.MOD_ID, "cop_hud")) { ctx, a ->
            if (mc.options.hideGui || mc.level == null || mc.player == null) return@attachElementBefore
            post(RenderEvent.Overlay(ctx, a))
        }
    }

    @JvmStatic
    fun onPacketReceived(packet: Packet<*>): Boolean {
        if (PacketEvent.Received(packet).post()) return true

        // Chat packet events remain synchronous because their public contract
        // is cancellable. All non-cancellable derived work is dispatched below.
        val cancelled = when (packet) {
            is ClientboundSystemChatPacket -> {
                val text = packet.content
                if (packet.overlay) ChatEvent.ActionBar(text.string, text).post() else ChatEvent.Packet(text.string, text).post()
            }
            else -> false
        }

        // Netty retains synchronous cancellation above. Only after every
        // cancellable pre-handler has accepted the packet do ordinary
        // observers receive it on the client thread. This notification is
        // pre-vanilla-state: it preserves the old observation point and does
        // not promise that the vanilla packet handler has already run.
        if (!cancelled && (
                events[PacketEvent.ReceivedClient::class.java]?.isEmpty() == false ||
                    packet is ClientboundPingPacket ||
                    packet is ClientboundTakeItemEntityPacket ||
                    packet is ClientboundRemoveEntitiesPacket ||
                    packet is ClientboundSoundPacket ||
                    packet is ClientboundSystemChatPacket
                )
        ) {
            mc.execute {
                PacketEvent.ReceivedClient(packet).post()
                onPacketReceivedClient(packet)
            }
        }
        return cancelled
    }

    private fun onPacketReceivedClient(packet: Packet<*>) {
        when (packet) {
            is ClientboundSystemChatPacket -> {
                val text = packet.content
                if (packet.overlay) ChatEvent.ActionBarClient(text.string, text).post()
                else ChatEvent.PacketClient(text.string, text).post()
            }
            is ClientboundPingPacket -> {
                if (packet.id < 0) {
                    totalTicks++
                    TickEvent.Server(totalTicks).post()
                }
            }
            is ClientboundTakeItemEntityPacket -> {
                val player = mc.player ?: return
                if (!Dungeon.inClear) return
                val itemEntity = mc.level?.getEntity(packet.itemId) as? ItemEntity ?: return
                if (itemEntity.item.hoverName.string.containsOneOf(dungeonItemDrops, true) &&
                    itemEntity.distanceTo(player as Entity) <= 6
                ) DungeonEvent.Secret.Item(itemEntity).post()
            }
            is ClientboundRemoveEntitiesPacket -> {
                val player = mc.player ?: return
                if (!Dungeon.inClear) return
                packet.entityIds.forEach { id ->
                    val entity = mc.level?.getEntity(id) as? ItemEntity ?: return@forEach
                    if (entity.item.hoverName.string.containsOneOf(dungeonItemDrops, true) &&
                        entity.distanceTo(player as Entity) <= 6
                    ) DungeonEvent.Secret.Item(entity).post()
                }
            }
            is ClientboundSoundPacket -> {
                if (Dungeon.inClear && packet.sound.value().equalsOneOf(SoundEvents.BAT_HURT, SoundEvents.BAT_DEATH) && packet.volume == 0.1f) {
                    DungeonEvent.Secret.Bat(packet).post()
                }
            }
        }
    }

    @JvmStatic
    fun onPacketSent(packet: Packet<*>): Boolean {
        if (PacketEvent.Sent(packet).post()) return true

        return when (packet) {
            is ServerboundUseItemOnPacket -> {
                if (!Dungeon.inDungeons || packet.hand == InteractionHand.OFF_HAND) return false
                val pos = packet.hitResult.blockPos
                DungeonEvent.Secret.Interact(pos, mc.level?.getBlockState(pos)?.takeIf { Dungeon.isSecret(it, pos) } ?: return false).post()
                false
            }
            else -> false
        }
    }

//    inline fun <reified T : Any> on(noinline callback: (T) -> Unit): EventListener = on(0, callback, true)
    inline fun <reified T : Event> on(priority: Int = 0, noinline callback: T.() -> Unit): EventListener = on(priority, callback, true)

    inline fun <reified T : Event> on(priority: Int = 0, noinline callback: T.() -> Unit, add: Boolean = true): EventListener {
        val eventClass = T::class.java
        val handlers = events.getOrPut(eventClass) { EventHandlers() }
        val prioritisedCallback = PrioritisedCallback(priority, callback)
        if (add) handlers.add(prioritisedCallback)
        return EventListenerImpl(prioritisedCallback, handlers)
    }

    @JvmName("onPacket")
    inline fun <reified E, reified P : Packet<*>> on(
        priority: Int = 0,
        noinline callback: PacketScope<E, P>.() -> Unit,
        add: Boolean = true
    ): EventListener where E : Event, E : PacketEvent {
        return on<E>(priority, {
            if (packet is P) callback(PacketScope(this, packet as P))
        }, add)
    }

    fun <T : Event> post(event: T): Boolean {
        val handlers = events[event::class.java] ?: return false
        val sortedHandlers = handlers.snapshot()
        if (sortedHandlers.isEmpty()) return false

        for (handler in sortedHandlers) {
            if (event is CancellableEvent && event.isCancelled()) {
                return true
            }
            try {
                @Suppress("UNCHECKED_CAST")
                (handler.callback as (T) -> Unit)(event)
            } catch (e: Exception) {
                if (reportedFailures.add(handler)) {
                    CopMod.logger.error(
                        "Event listener failed for ${event::class.java.name}; suppressing repeat errors from this listener",
                        e,
                    )
                }
            }
        }
        return if (event is CancellableEvent) event.isCancelled() else false
    }

    interface EventListener {
        fun remove(): Boolean?
        fun add(): Boolean
    }

    class EventListenerImpl(
        private val callback: PrioritisedCallback<*>,
        private val handlers: EventHandlers
    ) : EventListener {
        override fun remove(): Boolean = handlers.remove(callback).also { removed ->
            if (removed) reportedFailures.remove(callback)
        }

        override fun add(): Boolean = handlers.add(callback).also { added ->
            if (added) reportedFailures.remove(callback)
        }
    }

    @JvmStatic
    fun onPacketReceivedPost(packet: Packet<*>) {
        if (events[PacketEvent.ReceivedPost::class.java]?.isEmpty() == false) {
            mc.execute { PacketEvent.ReceivedPost(packet).post() }
        }
    }
}

class PacketScope<E : PacketEvent, P : Packet<*>>(val event: E, val packet: P) { // idkman
    fun cancel() {
        if (event is CancellableEvent) event.cancel()
    }
}
