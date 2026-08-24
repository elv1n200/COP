package cop.mixins;


import cop.CopMod;
import cop.api.input.CatKeyboard;
import cop.api.input.CatKeys;
import cop.mixininterfaces.IChatComponent;
import cop.mixininterfaces.ISearchMode;
import cop.mixins.accessors.ChatComponentAccessor;
import cop.utils.ChatSearchBuffer;
import cop.utils.UserRegex;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.awt.*;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
//? if >= 26 {
/*import net.minecraft.client.multiplayer.chat.GuiMessageSource;*/
//? }
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;

@Mixin(ChatScreen.class)
public class ChatScreenMixin extends Screen implements ISearchMode {
    protected ChatScreenMixin(Component title) { super(title); }

    @Unique
    private static boolean isSearchActive = false;
    @Unique
    private static final List<GuiMessage> messageBackup = new ObjectArrayList<>();
    @Unique
    private static final ChatSearchBuffer<GuiMessage, MessageSignature> queuedOperations = new ChatSearchBuffer<>();
    @Unique
    private static String textBeforeSearch = "";

    @Shadow
    protected EditBox input;

    @Unique
    @NotNull
    private final Minecraft mc = Minecraft.getInstance();

//    @Inject(
//            method = "sendMessage",
//            at = @At("HEAD"),
//            cancellable = true
//    )
//    private void onSendMessage(String message, boolean addToHistory, CallbackInfo ci) {
//        if (EventBus.INSTANCE.post(new ChatEvent.Sent(message))) ci.cancel();
//    }

    @Inject(
            method = "keyPressed",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onKeyPressed(KeyEvent input, CallbackInfoReturnable<Boolean> cir) {
        if (CatKeyboard.Modifier.INSTANCE.isCtrlDown() && input.input() == CatKeys.KEY_F) {
            toggleSearch(!isSearchActive);
            cir.setReturnValue(true);
            return;
        }

        if (isSearchActive && input.input() == CatKeys.KEY_ESCAPE) {
            toggleSearch(false);
            cir.setReturnValue(true);
        }
    }

    @Inject(
            method = "onEdited",
            at = @At("TAIL")
    )
    private void onChatInput(String text, CallbackInfo ci) {
        if (isSearchActive) {
            doSearch(text);
        }
    }

    @Inject(
            method = "removed",
            at = @At("TAIL")
    )
    private void onRemoved(CallbackInfo ci) {
        if (isSearchActive) toggleSearch(false);
    }

    @Unique
    private void toggleSearch(boolean activate) {
        isSearchActive = activate;
        ChatComponent chatHud = mc.gui.getChat();
        ChatComponentAccessor accessor = (ChatComponentAccessor) chatHud;

        if (activate) {
            messageBackup.clear();
            messageBackup.addAll(accessor.getMessages());
            // A previous replay failure leaves its failed suffix here for the
            // next close attempt. Successful drains leave the buffer empty.

            textBeforeSearch = input.getValue();
            input.setValue("");
            input.setTextColor(Color.YELLOW.getRGB());
            doSearch("");
        } else {
            try {
                accessor.getMessages().clear();
                accessor.getMessages().addAll(messageBackup);
                messageBackup.clear();

                IChatComponent replayTarget = (IChatComponent) chatHud;
                queuedOperations.drain(
                        (queuedMessage, copId) -> {
                            replayTarget.cop$beginSearchReplay(copId);
                            try {
                                //? if >= 26 {
                                /*accessor.cop$invokeAddMessage(
                                        queuedMessage.content(),
                                        queuedMessage.signature(),
                                        queuedMessage.source(),
                                        queuedMessage.tag()
                                );*/
                                //? } else {
                                chatHud.addMessage(queuedMessage.content());
                                //? }
                            } finally {
                                replayTarget.cop$endSearchReplay();
                            }
                        },
                        chatHud::deleteMessage
                );
            } catch (RuntimeException replayFailure) {
                CopMod.INSTANCE.getLogger().error(
                        "[cop] Failed to restore/replay chat search mutations; "
                                + queuedOperations.size() + " operation(s) retained for retry",
                        replayFailure
                );
            } finally {
                input.setValue(textBeforeSearch);
                input.setTextColor(-2039584);
                chatHud.rescaleChat();
            }
        }
    }

    @Unique
    private void doSearch(String query) {
        ChatComponent chatHud = mc.gui.getChat();
        ChatComponentAccessor accessor = (ChatComponentAccessor) chatHud;
        List<GuiMessage> messages = accessor.getMessages();

        messages.clear();

        if (query.isEmpty()) {
            messages.addAll(messageBackup);
        } else {
            List<GuiMessage> filteredResults;
            Pattern pattern = UserRegex.compilePattern(query, Pattern.CASE_INSENSITIVE);
            if (pattern != null) {
                filteredResults = messageBackup.stream()
                        .filter(msg -> UserRegex.containsMatch(pattern, msg.content().getString()))
                        .toList();
            } else {
                String literalQuery = query.toLowerCase(Locale.ROOT);
                filteredResults = messageBackup.stream()
                        .filter(msg -> msg.content().getString().toLowerCase(Locale.ROOT).contains(literalQuery))
                        .toList();
            }

            messages.addAll(filteredResults);
        }
        // 26.x GuiMessage record inserted a GuiMessageSource before the tag.
        //? if >= 26 {
        /*messages.addFirst(new GuiMessage(mc.gui.getGuiTicks(), Component.literal("§e§lSEARCH ON"), null, GuiMessageSource.SYSTEM_CLIENT, null));*/
        //? } else {
        messages.addFirst(new GuiMessage(mc.gui.getGuiTicks(), Component.literal("§e§lSEARCH ON"), null, null));
        //? }
        chatHud.rescaleChat();
    }

    @Override
    public boolean cop$isSearchActive() {
        return isSearchActive;
    }

    @Override
    //? if >= 26 {
    /*public void cop$queueMessage(Component message, MessageSignature signatureData, GuiMessageSource source, GuiMessageTag indicator, int copId) {
        queuedOperations.queueMessage(
                new GuiMessage(mc.gui.getGuiTicks(), message, signatureData, source, indicator),
                copId
        );
    }*/
    //? } else {
    public void cop$queueMessage(Component message, int copId) {
        queuedOperations.queueMessage(
                new GuiMessage(mc.gui.getGuiTicks(), message, null, null),
                copId
        );
    }
    //? }

    @Override
    public void cop$queueDeletion(MessageSignature signatureData) {
        queuedOperations.queueDeletion(signatureData);
    }
}
