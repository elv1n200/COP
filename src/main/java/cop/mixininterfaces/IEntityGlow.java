package cop.mixininterfaces;

public interface IEntityGlow {
    int cop$getGlowColour();
    void cop$setGlowColour(int colour);

    boolean cop$getForceGlow();
    void cop$setForceGlow(boolean value);
}

