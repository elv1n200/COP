package cop.utils

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.GuiMessage
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.gui.components.ImageButton
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.multiplayer.prediction.PredictiveAction
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import cop.CopMod.mc
import cop.mixininterfaces.IChatComponent
import cop.mixininterfaces.IGuiMessage
import cop.mixins.accessors.ChatComponentAccessor
import cop.mixins.accessors.InventoryAccessor
import cop.mixins.accessors.KeyMappingAccessor
import cop.mixins.accessors.MultiPlayerGameModeAccessor
import cop.mixins.accessors.ImageButtonAccessor

fun MultiPlayerGameMode.startPrediction(action: PredictiveAction) {
    val level = mc.level ?: return
    (this as MultiPlayerGameModeAccessor).invokeStartPrediction(level, action)
}

inline val ChatComponent.messages: MutableList<GuiMessage>
    get() = (this as ChatComponentAccessor).messages

inline val ChatComponent.visibleMessages: List<GuiMessage.Line>
    get() = (this as ChatComponentAccessor).visibleMessages

// These wrap @Invoker accessors that only exist on 1.21.10 (target methods
// were removed in 1.21.11). Compiled out on 1.21.11+ so the file still resolves.
//? if <= 1.21.10 {
fun ChatComponent.toChatLineMX(x: Double): Double =
    (this as ChatComponentAccessor).toChatLineMX(x)

fun ChatComponent.toChatLineMY(y: Double): Double =
    (this as ChatComponentAccessor).toChatLineMY(y)

fun ChatComponent.getMessageLineIdx(chatLineX: Double, chatLineY: Double): Int =
    (this as ChatComponentAccessor).getMessageLineIdx(chatLineX, chatLineY)
//?}

fun ChatComponent.refreshTrimmedMessages() =
    (this as ChatComponentAccessor).invokeRefreshTrimmedMessages()

inline var ChatComponent.scrolledLines: Int
    get() = (this as ChatComponentAccessor).scrolledLines
    set(value) {
        (this as ChatComponentAccessor).scrolledLines = value
    }

fun ChatComponent.add(text: Component, id: Int) =
    (this as IChatComponent).`cop$add`(text, id)

@Suppress("CAST_NEVER_SUCCEEDS")
inline var GuiMessage.id: Int
    get() = (this as IGuiMessage).`cop$getId`()
    set(value) {
        (this as IGuiMessage).`cop$setId`(value)
    }

inline val Inventory.items: List<ItemStack>
    get() = (this as InventoryAccessor).items

inline val KeyMapping.key: InputConstants.Key
    get() = (this as KeyMappingAccessor).key

inline val ImageButton.textures: WidgetSprites
    get() = (this as ImageButtonAccessor).textures

