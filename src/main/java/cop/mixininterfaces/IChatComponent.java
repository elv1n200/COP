package cop.mixininterfaces;

import net.minecraft.network.chat.Component;

public interface IChatComponent {
    void cop$add(Component message, int id);

    void cop$beginSearchReplay(int id);

    void cop$endSearchReplay();
}
