package cop.mixins;

import cop.api.events.ChatEvent;
import cop.mixininterfaces.IGuiMessage;
import cop.mixininterfaces.IChatComponent;
import cop.mixininterfaces.ISearchMode;
import cop.module.impl.misc.Chat;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
//? if >= 26 {
/*import net.minecraft.client.multiplayer.chat.GuiMessageSource;*/
//? }
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin implements IChatComponent {
    @Shadow
    @Final
    private List<GuiMessage.Line> trimmedMessages;
    @Shadow
    @Final
    private List<GuiMessage> allMessages;

    // 26.x split ChatComponent's single addMessage funnel: the public entry
    // points are addClientSystemMessage / addServerSystemMessage / addPlayerMessage,
    // all routing through a private 4-arg addMessage(...GuiMessageSource...). The
    // old 3-arg addMessage / 1-arg addMessage no longer exist publicly.
    //? if >= 26 {
    /*@Shadow
    public abstract void addClientSystemMessage(Component message);*/
    //? } else {
    @Shadow
    public abstract void addMessage(Component message, @Nullable MessageSignature signatureData, @Nullable GuiMessageTag indicator);
    @Shadow
    public abstract void addMessage(Component message);
    //? }

    @Unique
    private int nextId;
    @Unique
    private int preReplayId;
    @Unique
    private boolean suppressNextReplayReceive;

    @Override
    public void cop$add(Component message, int id) {

        if (id != 0) {
            trimmedMessages.removeIf(msg -> ((IGuiMessage) (Object) msg).cop$getId() == id);
            allMessages.removeIf(msg -> ((IGuiMessage) (Object) msg).cop$getId() == id);
        }

        int previousId = nextId;
        nextId = id;
        try {
            //? if >= 26 {
            /*addClientSystemMessage(message);*/
            //? } else {
            addMessage(message);
            //? }
        } finally {
            nextId = previousId;
        }
    }

    @Override
    public void cop$beginSearchReplay(int id) {
        preReplayId = nextId;
        nextId = id;
        suppressNextReplayReceive = true;
    }

    @Override
    public void cop$endSearchReplay() {
        suppressNextReplayReceive = false;
        nextId = preReplayId;
    }

    // 26.x uses Java 21's List.addFirst(Object); older versions insert at
    // index 0 through List.add(int, Object). Keep the injector descriptor and
    // argument index aligned with the actual bytecode in each version.
    //? if >= 26 {
    /*@ModifyArg(
            method = "addMessageToDisplayQueue",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;addFirst(Ljava/lang/Object;)V"
            ),
            index = 0
    )
    private Object onAddVisibleLine(Object line) {
        if (nextId != 0) {
            ((IGuiMessage) line).cop$setId(nextId);
        }
        return line;
    }*/
    //? } else {
    @ModifyArg(
            method = "addMessageToDisplayQueue",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;add(ILjava/lang/Object;)V"
            ),
            index = 1
    )
    private Object onAddVisibleLine(Object line) {
        if (nextId != 0) {
            ((IGuiMessage) line).cop$setId(nextId);
        }
        return line;
    }
    //? }

    @Inject(
            method = "addMessageToQueue(Lnet/minecraft/client/GuiMessage;)V",
            at = @At("TAIL")
    )
    private void onAddMessageAfterNewLine(GuiMessage message, CallbackInfo ci) {
        if (nextId != 0 && !allMessages.isEmpty()) {
            ((IGuiMessage) (Object) allMessages.getFirst()).cop$setId(nextId);
        }
    }

    // 26.x: the 3-arg addMessage and 1-arg addMessage are gone; the private
    // 4-arg addMessage(...GuiMessageSource...) is the single funnel every add
    // path routes through. Mixin @Inject requires the handler params to match
    // the target's signature exactly (not a prefix), so the whole handler is
    // version-branched even though the body only uses `message`.
    //? if >= 26 {
    /*@Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onAddMessage(Component message, MessageSignature signatureData, GuiMessageSource source, GuiMessageTag indicator, CallbackInfo ci) {
        if (suppressNextReplayReceive) {
            suppressNextReplayReceive = false;
            return;
        }
        if (new ChatEvent.Receive(message.getString(), message, nextId).post()) {
            ci.cancel();
            return;
        }

        Screen currentScreen = Minecraft.getInstance().screen;
        if (currentScreen instanceof ISearchMode searchScreen && searchScreen.cop$isSearchActive()) {
            searchScreen.cop$queueMessage(message, signatureData, source, indicator, nextId);
            ci.cancel();
        }
    }

    @Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V",
            at = @At("TAIL"),
            cancellable = true
    )
    private void onAddMessagePost(Component message, MessageSignature signatureData, GuiMessageSource source, GuiMessageTag indicator, CallbackInfo ci) {
        if (new ChatEvent.Receive.Post(message.getString(), message, nextId).post()) ci.cancel();
    }*/
    //? } else {
    @Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onAddMessage(Component message, MessageSignature signatureData, GuiMessageTag indicator, CallbackInfo ci) {
        if (new ChatEvent.Receive(message.getString(), message, nextId).post()) ci.cancel();
    }

    @Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
            at = @At("TAIL"),
            cancellable = true
    )
    private void onAddMessagePost(Component message, MessageSignature signatureData, GuiMessageTag indicator, CallbackInfo ci) {
        if (new ChatEvent.Receive.Post(message.getString(), message, nextId).post()) ci.cancel();
    }
    //? }

    //? if >= 26 {
    //? } else {
    @Inject(
            method = "addMessage(Lnet/minecraft/network/chat/Component;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptMessage(Component message, CallbackInfo ci) {
        if (suppressNextReplayReceive) return;
        Screen currentScreen = Minecraft.getInstance().screen;
        if (currentScreen instanceof ISearchMode searchScreen) {
            if (searchScreen.cop$isSearchActive()) {
                searchScreen.cop$queueMessage(message, nextId);
                ci.cancel();
            }
        }
    }
    //? }

    @Inject(
            method = "deleteMessage(Lnet/minecraft/network/chat/MessageSignature;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void interceptDeleteMessage(MessageSignature signatureData, CallbackInfo ci) {
        Screen currentScreen = Minecraft.getInstance().screen;
        if (currentScreen instanceof ISearchMode searchScreen && searchScreen.cop$isSearchActive()) {
            searchScreen.cop$queueDeletion(signatureData);
            ci.cancel();
        }
    }

    // In 26.1.2 tick() calls this private queue processor, whose removeIf
    // predicate invokes deleteMessageOrDelay(signature). Running it against
    // the search projection can remove the vanilla delayed-deletion entry
    // without updating the canonical backup. Pausing the processor preserves
    // that exact entry and its deletableAfter timestamp for the first tick
    // after search closes; no second COP deletion is queued or replayed.
    //? if >= 26 {
    /*@Inject(
            method = "processMessageDeletionQueue()V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pauseDelayedMessageDeletionsDuringSearch(CallbackInfo ci) {
        Screen currentScreen = Minecraft.getInstance().screen;
        if (currentScreen instanceof ISearchMode searchScreen && searchScreen.cop$isSearchActive()) {
            ci.cancel();
        }
    }
    *///? }

    // 1.21.11 added a second boolean param to `render`, so the implicit
    // discriminator can no longer pick "the boolean" — pin to ordinal = 0
    // (the first boolean = `focused`) on the new version.
    //? if <= 1.21.10 {
    @ModifyVariable(
            method = "render",
            at = @At("HEAD"),
            argsOnly = true
    )
    private boolean renderFocused(boolean focused) {
        return focused || Chat.INSTANCE.isDown();
    }
    //? } else {
    /*@ModifyVariable(
            method = "render",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0
    )
    private boolean renderFocused(boolean focused) {
        return focused || Chat.INSTANCE.isDown();
    }
    *///?}

    @ModifyExpressionValue(
            method = {"getHeight()I", "addMessageToDisplayQueue"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/ChatComponent;isChatFocused()Z"
            )
    )
    private boolean focusWhenPeeking(boolean original) {
        return original || Chat.INSTANCE.isDown();
    }

}
