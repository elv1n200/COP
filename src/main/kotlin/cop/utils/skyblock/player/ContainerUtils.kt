package cop.utils.skyblock.player

import it.unimi.dsi.fastutil.ints.Int2ObjectMaps
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.HashedStack
import net.minecraft.network.protocol.game.*
import net.minecraft.world.inventory.ClickType
import net.minecraft.world.item.ItemStack
import cop.CopMod.mc
import cop.annotations.Init
import cop.api.events.PacketEvent
import cop.api.events.WorldEvent
import cop.api.events.core.EventBus
import cop.api.events.core.EventBus.on
import cop.api.events.core.Priority
import cop.mixins.accessors.InventoryAccessor
import cop.module.impl.misc.PetKeybinds
import cop.utils.ChatUtils
import cop.utils.ChatUtils.modMessage
import cop.utils.Scheduler.scheduleTask
import cop.utils.Scheduler.scheduleTaskHandle
import cop.utils.StringUtils.noControlCodes
import cop.utils.items
import cop.utils.skyblock.ItemUtils.loreString
import cop.utils.skyblock.ItemUtils.skyblockUuid
import cop.utils.skyblock.player.ContainerUtils.closeContainer
import cop.utils.skyblock.player.ContainerUtils.getContainerItems
import cop.utils.skyblock.player.ContainerUtils.getContainerItemsClose
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

@Init
object ContainerUtils {
    @ConsistentCopyVisibility
    data class ContainerSession internal constructor(
        val containerId: Int,
        val stateId: Int,
        internal val worldEpoch: Long,
        internal val sessionEpoch: Long,
        internal val title: String,
    )

    data class ContainerSnapshot(
        val session: ContainerSession,
        val items: List<ItemStack?>,
    )

    private data class PendingReopenCancel(
        val title: String,
        val worldEpoch: Long,
        val sourceSessionEpoch: Long,
        val expiresAt: Long,
    )

    private val activeSessionRef = AtomicReference<ContainerSession?>(null)
    private val worldEpoch = AtomicLong(0L)
    private val sessionCounter = AtomicLong(0L)
    @Volatile private var pendingReopenCancel: PendingReopenCancel? = null

    private val activeSession: ContainerSession?
        get() = activeSessionRef.get()

    val containerId: Int
        get() = activeSession?.containerId ?: -1

    init {
        on<PacketEvent.Received> (Priority.HIGHEST + 1) { // more than highest to ensure some ret doesn't cancel it on highest prio
            when (packet) {
                is ClientboundOpenScreenPacket -> {
                    val currentWorld = worldEpoch.get()
                    val opened = ContainerSession(
                        containerId = packet.containerId,
                        stateId = 0,
                        worldEpoch = currentWorld,
                        sessionEpoch = sessionCounter.incrementAndGet(),
                        title = packet.title.string,
                    )
                    activeSessionRef.set(opened)
                    if (worldEpoch.get() != currentWorld) {
                        activeSessionRef.compareAndSet(opened, null)
                        return@on
                    }

                    val pending = pendingReopenCancel
                    pendingReopenCancel = null
                    if (pending != null &&
                        worldEpoch.get() == currentWorld &&
                        pending.worldEpoch == currentWorld &&
                        opened.sessionEpoch > pending.sourceSessionEpoch &&
                        System.currentTimeMillis() <= pending.expiresAt &&
                        opened.title.contains(pending.title, ignoreCase = true)
                    ) {
                        cancel()
                        closeContainer(opened)
                    }
                }
                is ClientboundContainerClosePacket -> {
                    clearSession(packet.containerId)
                }
                is ClientboundContainerSetSlotPacket -> {
                    updateState(packet.containerId, packet.stateId)
                }
                is ClientboundContainerSetContentPacket -> {
                    updateState(packet.containerId, packet.stateId)
                }
            }
        }
        on<PacketEvent.Sent> (Priority.HIGHEST + 1) {
            if (packet is ServerboundContainerClosePacket) clearSession(packet.containerId)
        }
        on<WorldEvent.Change> {
            worldEpoch.incrementAndGet()
            sessionCounter.incrementAndGet()
            activeSessionRef.set(null)
            pendingReopenCancel = null
        }
    }

    private fun updateState(containerId: Int, stateId: Int) {
        while (true) {
            val current = activeSessionRef.get() ?: return
            if (current.containerId != containerId) return
            if (activeSessionRef.compareAndSet(current, current.copy(stateId = stateId))) return
        }
    }

    private fun clearSession(containerId: Int) {
        while (true) {
            val current = activeSessionRef.get() ?: return
            if (current.containerId != containerId) return
            if (activeSessionRef.compareAndSet(current, null)) {
                sessionCounter.incrementAndGet()
                return
            }
        }
    }

    /**
     * Fetches items from a container and clicks the one matching the given skyblock UUID (and optional lore string).
     *
     * Only **one** of [uuid] or [name] should be provided.
     *
     * @param command The command to open the container (e.g., "petsmenu").
     * @param container The name of the container to open (e.g., "Pets")
     * @param uuid Optional skyblock UUID of the item to click.
     * @param name Optional display name of the item to click.
     * @param lore Optional lore string to further filter the item.
     * @param inContainer If true, clicks inside the container; if false,clicks in the player inventory.
     * @param slots The number of slots in the container (default 54).
     * @param timeout Maximum number of ticks to wait for all items (default 20).
     * @param button The mouse button to click (default 0 = left click).
     * @param shift Whether to shift-click the item (default false).
     * @param cancelReopen Whether to cancel container reopen (for example, when you swap masks in /eq menu it rebuilds the container)
     * @param closeAfterClick Closes only the captured menu session after the click.
     * @return `true` if the item was found and clicked successfully, `false` otherwise.
     *
     * Notes:
     *  - If the container fails to load within [timeout] ticks, returns `false`.
     *  - You should check for `false` to detect unsuccessful fetching.
     *  - This function cancels GUI rendering on the client side.
     *  - After fetching items, if the target item isn't found, the container is closed automatically.
     *  - After clicking the item it may not close the GUI server side. Use [closeContainer] if the item you're clicking doesn't close the container automatically.
     *
     *  @see [PetKeybinds.summonPet]
     */
    suspend fun getContainerItemsClick(
        command: String,
        container: String,
        uuid: String? = null,
        name: String? = null,
        lore: String? = null,
        inContainer: Boolean = true,
        slots: Int = 54,
        timeout: Int = 20,
        button: Int = 0,
        shift: Boolean = false,
        cancelReopen: Boolean = false,
        closeAfterClick: Boolean = false,
    ): Boolean {
        require(uuid != null || name != null) { "You must provide either uuid or name." }
        require(!(uuid != null && name != null)) { "Provide only one of uuid or name." }
        val inventory = mc.player?.inventory ?: return false

        val snapshot = getContainerSnapshot(command, container, slots, timeout) ?: return false
        val items = snapshot.items

        val invItems = inventory.items.take(36)
        val finalItems = if (inContainer) items else invItems

        val slot = finalItems.indexOfFirst { item ->
            val matchesUuid = uuid?.let { item.skyblockUuid == it } ?: true
            val matchesName = name?.let { item?.displayName?.string?.noControlCodes?.contains(it, ignoreCase = true) } ?: true
            val matchesLore = lore?.let { item.loreString?.contains(it, ignoreCase = true) == true } ?: true
            matchesUuid && matchesName && matchesLore
        }

        if (slot == -1) {
            closeContainer(snapshot.session)
            return false
        }
        val slotToCLick = if (inContainer) slot else {
            if (slot < 9) slots + 27 + slot // hotbar
            else slots + (slot - 9) // inventory
        }
        val clicked = clickAwait(snapshot.session, slotToCLick, button, shift)
        if (!clicked) {
            closeContainer(snapshot.session)
            return false
        }

        if (cancelReopen) armReopenCancel(container, snapshot.session)
        if (closeAfterClick) scheduleTask(2) { closeContainer(snapshot.session) }
        return true
    }

    /**
     * Opens a container via a command and fetches its items into a list.
     *
     * @param command The command to open the container (e.g., "petsmenu").
     * @param containerName The name of the container to open (e.g., "Pets")
     * @param slots The number of slots in the container (default 54).
     * @param timeout Maximum number of ticks to wait for all items (default 20).
     * @return A list of [ItemStack?] representing the container contents.
     *  *         Slots with no item are `null`.
     *  *         Returns an empty list if fetching fails, times out, or container could not be read.
     *
     *  Notes:
     *  - If the container fails to load within [timeout] ticks, returns `emptyList()`.
     *  - You should check for `emptyList()` to detect unsuccessful fetching.
     *  - This function cancels GUI rendering on the client side.
     *  - After fetching items, the container remains open server side. Use [closeContainer] if you want to close the container.
     *    If you want to automatically close
     *    it after fetching, use [getContainerItemsClose] instead.
     */
    suspend fun getContainerItems(
        command: String,
        containerName: String,
        slots: Int = 54,
        timeout: Int = 20,
    ): List<ItemStack?> = getContainerSnapshot(command, containerName, slots, timeout)?.items ?: emptyList()

    /**
     * Opens a hidden container and returns its contents together with the exact
     * world/menu/state identity which produced them. Consumers that act on the
     * result must pass [ContainerSnapshot.session] to [clickAwait] or
     * [closeContainer], preventing a resumed coroutine from touching a newer
     * menu which happens to reuse the same numeric container id.
     */
    suspend fun getContainerSnapshot(
        command: String,
        containerName: String,
        slots: Int = 54,
        timeout: Int = 20,
    ): ContainerSnapshot? = suspendCancellableCoroutine { cont ->
        val requestEpoch = worldEpoch.get()
        val items = MutableList<ItemStack?>(slots) { null }
        var ownedSession: ContainerSession? = null
        val complete = AtomicBoolean(false)

        var openWindowListener: EventBus.EventListener? = null
        var setSlotListener: EventBus.EventListener? = null
        var worldChangeListener: EventBus.EventListener? = null
        var timeoutTask: cop.utils.Scheduler.Task? = null

        fun cleanup() {
            openWindowListener?.remove()
            setSlotListener?.remove()
            worldChangeListener?.remove()
            timeoutTask?.cancel()
        }

        fun finish(result: ContainerSnapshot?) {
            if (!complete.compareAndSet(false, true)) return
            cleanup()
            if (cont.isActive) cont.resume(result)
        }

        openWindowListener = on<PacketEvent.Received> (Priority.LOWEST) {
            if (packet !is ClientboundOpenScreenPacket) return@on
            if (packet.title.string != containerName) return@on
            if (worldEpoch.get() != requestEpoch) {
                finish(null)
                return@on
            }
            val current = activeSession
            if (current == null ||
                current.worldEpoch != requestEpoch ||
                current.containerId != packet.containerId ||
                current.title != containerName
            ) return@on
            ownedSession = current
            cancel()
            openWindowListener?.remove()
        }

        setSlotListener = on<PacketEvent.Received> (Priority.LOWEST) {
            when (val update = packet) {
                is ClientboundContainerSetContentPacket -> {
                    val session = ownedSession ?: return@on
                    val current = activeSession ?: return@on
                    if (worldEpoch.get() != requestEpoch ||
                        current.worldEpoch != requestEpoch ||
                        !sameIdentity(current, session) ||
                        update.containerId != session.containerId ||
                        update.items.size < slots
                    ) return@on
                    repeat(slots) { slot ->
                        val stack = update.items[slot]
                        items[slot] = stack.takeUnless(ItemStack::isEmpty)
                    }
                    finish(ContainerSnapshot(current, items.toList()))
                }

                is ClientboundContainerSetSlotPacket -> {
                    val session = ownedSession ?: return@on
                    val current = activeSession ?: return@on
                    if (worldEpoch.get() != requestEpoch ||
                        current.worldEpoch != requestEpoch ||
                        !sameIdentity(current, session) ||
                        update.containerId != session.containerId
                    ) return@on
                    val slot = update.slot
                    if (slot !in 0..<slots) return@on
                    items[slot] = update.item.takeUnless(ItemStack::isEmpty)
                    if (slot == slots - 1) finish(ContainerSnapshot(current, items.toList()))
                }
            }
        }

        worldChangeListener = on<WorldEvent.Change> {
            finish(null)
        }

        timeoutTask = scheduleTaskHandle(timeout) {
            if (!complete.get()) {
                modMessage("&cError: fetching items. timed out")
                ownedSession?.let { closeContainer(it) }
                finish(null)
            }
        }

        cont.invokeOnCancellation {
            if (complete.compareAndSet(false, true)) {
                cleanup()
            }
            val session = ownedSession
            mc.execute {
                if (session != null) closeContainer(session)
            }
        }

        // Install all listeners before sending the command so a fast server
        // response cannot race past the open-screen listener.
        if (cont.isActive && !complete.get() && worldEpoch.get() == requestEpoch) {
            ChatUtils.command(command)
        } else {
            finish(null)
        }
    }

    /**
     * Same as [getContainerItems] but automatically closes the container afterward.
     *
     * @see [PetKeybinds.getPets]
     */
    suspend fun getContainerItemsClose(command: String, containerName: String, slots: Int = 54, timeout: Int = 20): List<ItemStack?> {
        val snapshot = getContainerSnapshot(command, containerName, slots, timeout) ?: return emptyList()
        closeContainer(snapshot.session)
        return snapshot.items
    }

    fun LocalPlayer.clickSlot(slot: Int, containerId: Int = ContainerUtils.containerId, button: Int = 0, shift: Boolean = false) {
        if (containerId == -1) return

        val clickType = when {
            button == 2 -> ClickType.CLONE
            shift -> ClickType.QUICK_MOVE
            else -> ClickType.PICKUP
        }

        mc.gameMode?.handleInventoryMouseClick(containerId, slot, button, clickType, this)
    }

    fun click(slot: Int, button: Int = 0, shift: Boolean = false): Boolean {
        val session = activeSession ?: return false
        return enqueueClick(session, slot, button, shift)
    }

    suspend fun clickAwait(
        session: ContainerSession,
        slot: Int,
        button: Int = 0,
        shift: Boolean = false,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val queued = enqueueClick(
            session = session,
            slot = slot,
            button = button,
            shift = shift,
            canSend = { cont.isActive },
            completion = { sent -> if (cont.isActive) cont.resume(sent) },
        )
        if (!queued && cont.isActive) cont.resume(false)
    }

    private fun enqueueClick(
        session: ContainerSession,
        slot: Int,
        button: Int,
        shift: Boolean,
        canSend: () -> Boolean = { true },
        completion: (Boolean) -> Unit = {},
    ): Boolean {
        if (slot < 0 || !sameState(activeSession, session)) return false
        val clickType = when {
            button == 2 -> ClickType.CLONE
            shift -> ClickType.QUICK_MOVE
            else -> ClickType.PICKUP
        }

        scheduleTask {
            if (!canSend() || !sameState(activeSession, session)) {
                completion(false)
                return@scheduleTask
            }
            val connection = mc.connection
            if (connection == null) {
                completion(false)
                return@scheduleTask
            }
            connection.send(
                ServerboundContainerClickPacket(
                    session.containerId,
                    session.stateId,
                    slot.toShort(),
                    button.toByte(),
                    clickType,
                    Int2ObjectMaps.emptyMap(),
                    HashedStack.EMPTY
                )
            )
            completion(true)
        }
        return true
    }

    fun closeContainer(expectedContainerId: Int? = null): Boolean {
        val session = activeSession ?: return false
        if (expectedContainerId != null && session.containerId != expectedContainerId) return false
        return closeContainer(session)
    }

    fun closeContainer(session: ContainerSession): Boolean {
        if (!sameIdentity(activeSession, session)) return false
        scheduleTask {
            if (!sameIdentity(activeSession, session)) return@scheduleTask
            mc.connection?.send(ServerboundContainerClosePacket(session.containerId))
        }
        return true
    }

    private fun armReopenCancel(title: String, source: ContainerSession) {
        pendingReopenCancel = PendingReopenCancel(
            title = title,
            worldEpoch = source.worldEpoch,
            sourceSessionEpoch = source.sessionEpoch,
            expiresAt = System.currentTimeMillis() + REOPEN_CANCEL_TIMEOUT_MS,
        )
    }

    private fun sameIdentity(current: ContainerSession?, expected: ContainerSession): Boolean =
        current != null &&
            current.containerId == expected.containerId &&
            current.worldEpoch == expected.worldEpoch &&
            current.sessionEpoch == expected.sessionEpoch &&
            expected.worldEpoch == worldEpoch.get()

    private fun sameState(current: ContainerSession?, expected: ContainerSession): Boolean =
        sameIdentity(current, expected) && current?.stateId == expected.stateId

    private const val REOPEN_CANCEL_TIMEOUT_MS = 1_500L
}
