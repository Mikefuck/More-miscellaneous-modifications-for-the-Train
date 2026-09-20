package com.habitrain.lottery.skin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.habitrain.lottery.HabiLotteryMod;
import com.habitrain.lottery.api.skin.HabiSkinApi;
import com.habitrain.lottery.api.skin.SkinDefinition;
import com.habitrain.lottery.api.skin.SkinRegistrar;
import io.wifi.starrailexpress.util.ItemSkinManager;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Registers custom weapon skins from the fused content pack into SRE {@link ItemSkinManager}.
 * Replaces the broken reflective SkinManager path from starrail-express-item-skin-mod.
 */
public final class SkinContentBootstrap {
    private static final Gson GSON = new Gson();
    private static boolean externalEntrypointsLoaded;

    private SkinContentBootstrap() {
    }

    public static void registerAll() {
        int bundledCount = 0;
        JsonArray skins = loadSkinArray();
        if (skins == null || skins.isEmpty()) {
            HabiLotteryMod.LOGGER.error("No skins.json found — skin registration skipped");
        } else {
            for (JsonElement el : skins) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject obj = el.getAsJsonObject();
                String type = obj.has("type") ? obj.get("type").getAsString() : null;
                String id = obj.has("id") ? obj.get("id").getAsString() : null;
                if (type == null || id == null || type.isBlank() || id.isBlank()) {
                    continue;
                }
                type = type.trim().toLowerCase(java.util.Locale.ROOT);
                id = id.trim().toLowerCase(java.util.Locale.ROOT);
                try {
                    if (HabiSkinApi.register(SkinDefinition.builder(type, id, colorFor(type)).build())) {
                        bundledCount++;
                    }
                } catch (Throwable t) {
                    HabiLotteryMod.LOGGER.warn("Failed to register skin {}/{}: {}", type, id, t.toString());
                }
            }
        }
        loadExternalEntrypoints();
        HabiLotteryMod.LOGGER.info(
                "Registered {} bundled skins and {} total skins through API v{}",
                bundledCount, HabiSkinApi.size(), HabiSkinApi.API_VERSION);
    }

    public static int getRegisteredCount() {
        return HabiSkinApi.size();
    }

    public static void reRegister() {
        HabiSkinApi.reRegisterAll();
    }

    /**
     * Runs every third-party {@code habitrain_lottery:skin_registrar} entrypoint.
     *
     * <p><b>Audit S-04: a broken skin provider must not take the lottery offline.</b> This
     * runs from {@code HabiLotteryMod.onInitialize()}, so letting a registrar's throwable
     * escape would abort the whole mod (lottery + mailbox + titles) with an error that
     * points at the lottery instead of at the failing provider. Each failure is therefore
     * logged at ERROR with its mod id and throwable, the registrar is skipped, and the
     * remaining registrars still run; a final WARN lists every skipped mod id so the
     * degradation is impossible to miss.
     */
    private static synchronized void loadExternalEntrypoints() {
        if (externalEntrypointsLoaded) {
            return;
        }
        externalEntrypointsLoaded = true;
        List<EntrypointContainer<SkinRegistrar>> containers = FabricLoader.getInstance()
                .getEntrypointContainers(HabiSkinApi.ENTRYPOINT, SkinRegistrar.class)
                .stream()
                .sorted(Comparator.comparing(container -> container.getProvider().getMetadata().getId()))
                .toList();
        List<String> skippedProviders = new java.util.ArrayList<>();
        for (EntrypointContainer<SkinRegistrar> container : containers) {
            String provider = container.getProvider().getMetadata().getId();
            try {
                container.getEntrypoint().registerSkins();
                HabiLotteryMod.LOGGER.info("Loaded skin API registrations from {}", provider);
            } catch (Throwable t) {
                skippedProviders.add(provider);
                HabiLotteryMod.LOGGER.error(
                        "Skin API registrar failed for mod {} — skipping that provider, "
                                + "continuing with the remaining registrars",
                        provider, t);
            }
        }
        if (!skippedProviders.isEmpty()) {
            HabiLotteryMod.LOGGER.warn(
                    "Skipped {} broken skin API registrar(s): {} — the lottery, mailbox and titles "
                            + "still started, but skins registered by those mod(s) are missing",
                    skippedProviders.size(), String.join(", ", skippedProviders));
        }
    }

    private static JsonArray loadSkinArray() {
        // Prefer external override next to game config
        Path override = FabricLoader.getInstance().getConfigDir().resolve("habitrain_lottery/skins.json");
        if (Files.isRegularFile(override)) {
            try (InputStream in = Files.newInputStream(override);
                 InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                if (root != null && root.has("skins")) {
                    return root.getAsJsonArray("skins");
                }
            } catch (Exception e) {
                HabiLotteryMod.LOGGER.warn("Failed reading override skins.json: {}", e.toString());
            }
        }
        try (InputStream in = SkinContentBootstrap.class.getClassLoader()
                .getResourceAsStream("data/habitrain_lottery/defaults/skins.json")) {
            if (in == null) {
                return null;
            }
            try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonObject root = GSON.fromJson(reader, JsonObject.class);
                return root != null && root.has("skins") ? root.getAsJsonArray("skins") : null;
            }
        } catch (Exception e) {
            HabiLotteryMod.LOGGER.error("Failed reading bundled skins.json", e);
            return null;
        }
    }

    /** Matches old skin mod colorFor(type) palette. */
    public static int colorFor(String type) {
        if (type == null) {
            return 0xFFB8B8CC; // -4669236
        }
        return switch (type) {
            case "knife" -> 0xFF8F8F7F; // -7351297 signed as 0xFF8F8F7F? old const -7351297
            case "revolver" -> -18325;
            case "bat" -> -5839967;
            case "grenade" -> -24765;
            default -> -4669236;
        };
    }
}
