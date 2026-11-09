package com.habitrain.lottery.network;

import com.habitrain.lottery.HabiLotteryMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Admin action feedback packet supporting both server fallback strings and client-resolved
 * bilingual translation keys ({@code N-02}).
 */
public record AdminActionResultS2C(
        String message,
        boolean refreshPlayers,
        String translationKey,
        List<String> translationArgs
) implements CustomPacketPayload {
    public static final int MAX_MESSAGE_CHARS = 512;
    public static final int MAX_KEY_CHARS = 128;
    public static final int MAX_ARGS = 8;
    public static final int MAX_ARG_CHARS = 256;

    public static final Type<AdminActionResultS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(HabiLotteryMod.MOD_ID, "admin_result_v2"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AdminActionResultS2C> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUtf(value.message == null ? "" : value.message, MAX_MESSAGE_CHARS);
                buf.writeBoolean(value.refreshPlayers);
                buf.writeUtf(value.translationKey == null ? "" : value.translationKey, MAX_KEY_CHARS);
                List<String> args = value.translationArgs == null ? List.of() : value.translationArgs;
                int count = Math.min(MAX_ARGS, args.size());
                buf.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    String arg = args.get(i);
                    buf.writeUtf(arg == null ? "" : arg, MAX_ARG_CHARS);
                }
            },
            buf -> {
                String msg = buf.readUtf(MAX_MESSAGE_CHARS);
                boolean refresh = buf.readBoolean();
                String key = buf.readUtf(MAX_KEY_CHARS);
                int count = Math.max(0, Math.min(MAX_ARGS, buf.readVarInt()));
                List<String> args = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    args.add(buf.readUtf(MAX_ARG_CHARS));
                }
                return new AdminActionResultS2C(msg, refresh, key, List.copyOf(args));
            }
    );

    public AdminActionResultS2C {
        message = message == null ? "" : message;
        translationKey = translationKey == null ? "" : translationKey;
        translationArgs = translationArgs == null || translationArgs.isEmpty()
                ? List.of()
                : List.copyOf(translationArgs);
    }

    public AdminActionResultS2C(String message, boolean refreshPlayers) {
        this(message, refreshPlayers, "", List.of());
    }

    public static AdminActionResultS2C translated(String key, String fallbackMessage, Object... args) {
        return translatedWithRefresh(key, fallbackMessage, false, args);
    }

    public static AdminActionResultS2C translatedWithRefresh(
            String key,
            String fallbackMessage,
            boolean refreshPlayers,
            Object... args
    ) {
        List<String> encodedArgs = new ArrayList<>(args == null ? 0 : Math.min(MAX_ARGS, args.length));
        if (args != null) {
            for (int i = 0; i < args.length && i < MAX_ARGS; i++) {
                Object arg = args[i];
                String s = arg == null ? "" : String.valueOf(arg);
                if (s.length() > MAX_ARG_CHARS) {
                    s = s.substring(0, MAX_ARG_CHARS);
                }
                encodedArgs.add(s);
            }
        }
        String safeFallback = fallbackMessage == null ? "" : fallbackMessage;
        if (safeFallback.length() > MAX_MESSAGE_CHARS) {
            safeFallback = safeFallback.substring(0, MAX_MESSAGE_CHARS);
        }
        return new AdminActionResultS2C(safeFallback, refreshPlayers, key, encodedArgs);
    }

    /** Resolves this result in the active client locale ({@code @key} args resolve as nested translatables). */
    public Component toComponent() {
        if (translationKey != null && !translationKey.isBlank()) {
            Object[] resolved = new Object[translationArgs.size()];
            for (int i = 0; i < translationArgs.size(); i++) {
                String arg = translationArgs.get(i);
                resolved[i] = arg != null && arg.startsWith("@") && arg.length() > 1
                        ? Component.translatable(arg.substring(1))
                        : (arg == null ? "" : arg);
            }
            return Component.translatable(translationKey, resolved);
        }
        return Component.literal(message);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
