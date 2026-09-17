package com.habitrain.lottery.api.skin;

/**
 * Fabric entrypoint for registering HabiTrain skins.
 *
 * <p>Implement this interface and expose it through the
 * {@value HabiSkinApi#ENTRYPOINT} entrypoint in {@code fabric.mod.json}.
 * Registrars run on both the physical client and server before skin models are
 * collected, so implementations must return the same registrations on both
 * sides.</p>
 */
@FunctionalInterface
public interface SkinRegistrar {
    void registerSkins();
}
