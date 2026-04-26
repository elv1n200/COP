package cop.mixininterfaces;

import net.minecraft.network.chat.Component;

public interface ISearchMode {
    boolean cop$isSearchActive();

    void cop$queueMessage(Component message);
}
