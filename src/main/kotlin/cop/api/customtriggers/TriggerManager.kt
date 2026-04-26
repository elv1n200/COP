package cop.api.customtriggers

import cop.api.customtriggers.actions.HideMessageAction
import cop.api.customtriggers.actions.SendMessageAction
import cop.api.customtriggers.actions.TriggerAction
import cop.api.customtriggers.conditions.MessageCondition
import cop.api.customtriggers.conditions.PositionCondition
import cop.api.customtriggers.conditions.TriggerCondition
import cop.api.events.ChatEvent
import cop.api.events.KeyEvent
import cop.api.events.PacketEvent
import cop.api.events.TickEvent
import cop.api.events.core.EventBus.on
import cop.config.ConfigMap
import cop.config.configMap
import cop.config.typedEntries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.phys.AABB
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.getValue

object TriggerManager {
    val triggers: ConfigMap<String, MutableList<Trigger>> by configMap("custom_triggers.json")

    val conditionEntries by lazy { typedEntries<TriggerCondition>() }
    val actionEntries by lazy { typedEntries<TriggerAction>() }

    private val actionQueue = ConcurrentLinkedQueue<QueuedAction>()

    private data class QueuedAction(
        val action: DelayedAction,
        val context: TriggerContext,
        var ticksRemaining: Int,
        val data: Map<String, String>
    )

    fun init() {
        on<TickEvent.End> {
            stupid()
            handleEvent(TriggerContext.Tick)
        }

        on<ChatEvent.Receive.Post> {
            val context = TriggerContext.Chat(message)
            handleEvent(context)
            if (context.cancelled) cancel()
        }

        on<KeyEvent.Press> {
            handleEvent(TriggerContext.Key(key))
        }

        on<PacketEvent.Received> {
            when (packet) {
                is ClientboundSoundPacket -> {
                    handleEvent(TriggerContext.Sound(packet.sound.registeredName, packet.volume, packet.pitch))
                }
            }
        }
    }

    fun addTrigger(group: String, trigger: Trigger) {
        val list = triggers.getOrPut(group) { mutableListOf() }
        list.add(trigger)
        triggers.save()
    }

    private fun handleEvent(ctx: TriggerContext) {
        triggers.values.flatten().forEach { trigger ->
            if (!trigger.enabled) return@forEach
            ctx.data.clear()

            if (trigger.conditions.all { it.matches(ctx) }) {
                if (trigger.state) return@forEach
                trigger.state = true
                trigger.actions.forEach { action ->
                    if (action.delay > 0) actionQueue.add(QueuedAction(action, ctx, action.delay, HashMap(ctx.data)))
                    else action.action.execute(ctx)
                }
            } else {
                trigger.state = false
            }
        }
    }

    private fun stupid() {
        val iterator = actionQueue.iterator()
        while (iterator.hasNext()) {
            val action = iterator.next()
            action.ticksRemaining--

            if (action.ticksRemaining <= 0) {
                action.context.data.clear()
                action.context.data.putAll(action.data)

                action.action.action.execute(action.context)
                iterator.remove()
            }
        }
    }
}

class TriggerBuilder { // temp
    private val conditions = mutableListOf<TriggerCondition>()
    private val actions = mutableListOf<DelayedAction>()

    fun addCondition(condition: TriggerCondition): TriggerBuilder {
        conditions.add(condition)
        return this
    }

    fun addAction(action: TriggerAction, delay: Int = 0): TriggerBuilder {
        actions.add(DelayedAction(action, delay))
        return this
    }

    fun build(): Trigger {
        return Trigger(UUID.randomUUID().toString(), "some name", true, conditions, actions)
    }
}

fun testTrigger() = TriggerBuilder()
    .addCondition(PositionCondition(AABB(66.0, 65.0, 109.0, 67.0, 66.0, 110.0)))
    .addCondition(MessageCondition("TEST"))
    .addAction(HideMessageAction())
    .addAction(SendMessageAction("ABOB"))
    .build()