package cop.mixininterfaces;

import com.mojang.authlib.GameProfile;

public interface IGuiMessage {
    String cop$getText();

    int cop$getId();

    void cop$setId(int id);

    GameProfile cop$getSender();

    void cop$setSender(GameProfile profile);
}