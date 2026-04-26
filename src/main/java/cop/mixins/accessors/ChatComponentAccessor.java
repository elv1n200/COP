package cop.mixins.accessors;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;

@Mixin(ChatComponent.class)
public interface ChatComponentAccessor {
    @Accessor("allMessages")
    List<GuiMessage> getMessages();

    @Accessor("trimmedMessages")
    List<GuiMessage.Line> getVisibleMessages();

    // The screen-to-chat-coord helpers below were removed in 1.21.11 (clickable
    // text now goes through `captureClickableText`). Compiled out on 1.21.11+
    // to avoid harmless-but-noisy mixin remap warnings at build time.
    //? if <= 1.21.10 {
    @Invoker("screenToChatX")
    double toChatLineMX(double x);

    @Invoker("screenToChatY")
    double toChatLineMY(double y);

    @Invoker("getMessageLineIndexAt")
    int getMessageLineIdx(double chatLineX, double chatLineY);
    //?}

    @Invoker
    void invokeRefreshTrimmedMessages();

    @Accessor("chatScrollbarPos")
    int getScrolledLines();

    @Accessor("chatScrollbarPos")
    void setScrolledLines(int value);

    // `getWidth()` / `getScale()` were public in 1.21.10 but became private in
    // 1.21.11. Routing through `@Invoker` works on both versions, with a
    // tame method-name remap.
    @Invoker("getWidth")
    int invokeGetWidth();

    @Invoker("getScale")
    double invokeGetScale();
}
