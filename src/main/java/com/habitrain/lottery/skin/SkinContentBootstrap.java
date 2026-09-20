package com.habitrain.lottery.skin;

import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinRegistrar;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Empty by default: only explicitly installed skin providers contribute content. */
public final class SkinContentBootstrap {
    /** Metadata key an extension may declare to advertise the skin API version it targets. */
    private static final String API_METADATA_KEY = "habitrain_lottery:skin_api";
    /** Metadata key for the effects sub-API added in 1.1.18 (skin API v2 + effects v1). */
    private static final String EFFECTS_METADATA_KEY = "habitrain_lottery:skin_effects";
    /** {@code depends} / {@code provides} alias usable as a hard version gate. */
    private static final String API_PROVIDES_ID = "habitrain_lottery_skin_api";
    /** Hard version gate alias for the effects sub-API. */
    private static final String EFFECTS_PROVIDES_ID = com.habitrain.lottery.api.skin.SkinEffects.PROVIDES_ID;

    private static boolean externalEntrypointsLoaded;
    private static List<String> lastSkippedProviders = List.of();
    private SkinContentBootstrap() {}
    public static void registerAll() { loadExternalEntrypoints(); }
    public static int getRegisteredCount() { return HabiSkinApi.size(); }

    /** Providers that failed their last registration pass, in fail order. */
    public static synchronized List<String> getSkippedProviders() { return lastSkippedProviders; }

    /**
     * Runs every skin entrypoint again against the current catalogue.
     *
     * <p>{@code /hlt skins reregister} is the operator's recovery path, so this is a
     * real re-run rather than a no-op: registrars are idempotent by contract (an
     * identical re-registration returns {@code false}), which makes a replay safe.
     * A provider that fails now is reported through {@link #getSkippedProviders()}
     * rather than being presented as a success.</p>
     *
     * @return the catalogue size after the replay
     */
    public static int reRegister() {
        synchronized (SkinContentBootstrap.class) {
            externalEntrypointsLoaded = false;
        }
        return loadExternalEntrypoints();
    }

    private static synchronized int loadExternalEntrypoints() {
        if (externalEntrypointsLoaded) {
            return HabiSkinApi.size();
        }
        List<EntrypointContainer<SkinRegistrar>> containers = FabricLoader.getInstance()
                .getEntrypointContainers(HabiSkinApi.ENTRYPOINT, SkinRegistrar.class)
                .stream()
                .sorted(Comparator.comparing(container -> container.getProvider().getMetadata().getId()))
                .toList();
        List<String> skippedProviders = new ArrayList<>();
        for (EntrypointContainer<SkinRegistrar> container : containers) {
            String provider = container.getProvider().getMetadata().getId();
            warnOnApiVersion(provider);
            warnOnEffectsVersion(provider);
            // Attribute every registration made below to this provider, so a registrar
            // that throws halfway through can be rolled back instead of leaving a
            // half-registered catalogue behind.
            String previousProvider = HabiSkinApi.beginProvider(provider);
            try {
                container.getEntrypoint().registerSkins();
                HabiLotteryMod.LOGGER.info("Loaded skin API registrations from {}", provider);
            } catch (Throwable t) {
                int rolledBack = HabiSkinApi.removeProvider(provider);
                // The effects registries are attributed to the same provider, so a failing
                // registrar must not leave half of its effects live either.
                int effectsRolledBack = com.habitrain.lottery.api.skin.SkinEffects.removeProvider(provider);
                skippedProviders.add(provider);
                HabiLotteryMod.LOGGER.error(
                        "Skin API registrar failed for mod {} — rolled back {} partial registration(s) "
                                + "and {} effect(s) from that provider and continued with the remaining registrars",
                        provider, rolledBack, effectsRolledBack, t);
            } finally {
                HabiSkinApi.endProvider(previousProvider);
            }
        }
        lastSkippedProviders = List.copyOf(skippedProviders);
        if (!skippedProviders.isEmpty()) {
            HabiLotteryMod.LOGGER.warn(
                    "Skipped {} broken skin API registrar(s): {} — the lottery, mailbox and titles "
                            + "still started, but skins registered by those mod(s) are missing. "
                            + "Fix the extension and run /hlt skins reregister, or restart both sides",
                    skippedProviders.size(), String.join(", ", skippedProviders));
        }
        externalEntrypointsLoaded = true;
        return HabiSkinApi.size();
    }

    /**
     * Same treatment for the effects sub-API (1.1.18+): declaring it is optional, but a
     * declared number that disagrees with this build is always worth a WARN — that is the
     * difference between "my animation does nothing" and a one-line diagnosis.
     */
    private static void warnOnEffectsVersion(String provider) {
        ModContainer container = FabricLoader.getInstance().getModContainer(provider).orElse(null);
        if (container == null) {
            return;
        }
        if (container.getMetadata().getDependencies().stream()
                .anyMatch(dep -> EFFECTS_PROVIDES_ID.equals(dep.getModId()))) {
            return;
        }
        CustomValue declared = container.getMetadata().getCustomValue(EFFECTS_METADATA_KEY);
        if (declared == null) {
            return;
        }
        if (declared.getType() != CustomValue.CvType.OBJECT) {
            HabiLotteryMod.LOGGER.warn(
                    "Skin provider {} declares custom.{} as {}, expected an object with a numeric 'version'",
                    provider, EFFECTS_METADATA_KEY, declared.getType());
            return;
        }
        CustomValue versionValue = declared.getAsObject().get("version");
        int declaredVersion = versionValue != null && versionValue.getType() == CustomValue.CvType.NUMBER
                ? versionValue.getAsNumber().intValue()
                : -1;
        if (declaredVersion != com.habitrain.lottery.api.skin.SkinEffects.API_VERSION) {
            HabiLotteryMod.LOGGER.warn(
                    "Skin provider {} targets skin effects API v{} but this build provides v{}. "
                            + "Recompile the extension against the current API",
                    provider, declaredVersion < 0 ? "?" : declaredVersion,
                    com.habitrain.lottery.api.skin.SkinEffects.API_VERSION);
        }
    }

    /**
     * Reads the extension's declared skin-API version and warns on a mismatch.
     *
     * <p>Nothing here can be enforced at runtime, but a silent mismatch is worse:
     * a v1-era registrar does not implement {@link SkinRegistrar} at all, so the
     * failure surfaces later as an opaque {@code ClassCastException} swallowed by
     * the loop below. Naming the required version up front gives the operator a
     * diagnosis instead.</p>
     */
    private static void warnOnApiVersion(String provider) {
        ModContainer container = FabricLoader.getInstance().getModContainer(provider).orElse(null);
        if (container == null) {
            return;
        }
        if (container.getMetadata().getDependencies().stream()
                .anyMatch(dep -> API_PROVIDES_ID.equals(dep.getModId()))) {
            // Declared a hard dependency on the provided alias: the loader already
            // resolved it, so the version contract is enforced before we get here.
            return;
        }
        CustomValue declared = container.getMetadata().getCustomValue(API_METADATA_KEY);
        if (declared == null || declared.getType() != CustomValue.CvType.OBJECT) {
            HabiLotteryMod.LOGGER.warn(
                    "Skin provider {} does not declare custom.{} .version (expected {}). "
                            + "Add it, or depends {{\"{}\": \"*\"}}, so a future API change is diagnosable",
                    provider, API_METADATA_KEY, HabiSkinApi.API_VERSION, API_PROVIDES_ID);
            return;
        }
        CustomValue versionValue = declared.getAsObject().get("version");
        int declaredVersion = -1;
        if (versionValue != null && versionValue.getType() == CustomValue.CvType.NUMBER) {
            declaredVersion = versionValue.getAsNumber().intValue();
        }
        if (declaredVersion != HabiSkinApi.API_VERSION) {
            HabiLotteryMod.LOGGER.warn(
                    "Skin provider {} targets skin API v{} but this build provides v{}. "
                            + "Recompile the extension against the current API; v1 registrars do not "
                            + "implement SkinRegistrar and will be skipped",
                    provider, declaredVersion < 0 ? "?" : declaredVersion, HabiSkinApi.API_VERSION);
        }
    }
}
