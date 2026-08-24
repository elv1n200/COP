package cop.mixininterfaces;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.client.GuiMessageTag;
//? if >= 26 {
/*import net.minecraft.client.multiplayer.chat.GuiMessageSource;*/
//? }

import org.jetbrains.annotations.Nullable;

public interface ISearchMode {
    boolean cop$isSearchActive();

    //? if >= 26 {
    /*void cop$queueMessage(
            Component message,
            @Nullable MessageSignature signatureData,
            GuiMessageSource source,
            @Nullable GuiMessageTag indicator,
            int copId
    );*/
    //? } else {
    void cop$queueMessage(Component message, int copId);
    //? }

    void cop$queueDeletion(MessageSignature signatureData);
}
