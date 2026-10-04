package com.habitrain.lottery.prelaunch;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the multiplayer list ({@code servers.dat}) without touching Minecraft's NBT classes,
 * which must not be loaded before mixins are set up.
 */
public final class ServerListReader {

    public record ServerEntry(String name, String ip) {
    }

    private static final int TAG_END = 0;
    private static final int TAG_BYTE = 1;
    private static final int TAG_SHORT = 2;
    private static final int TAG_INT = 3;
    private static final int TAG_LONG = 4;
    private static final int TAG_FLOAT = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_LIST = 9;
    private static final int TAG_COMPOUND = 10;
    private static final int TAG_INT_ARRAY = 11;
    private static final int TAG_LONG_ARRAY = 12;
    private static final int MAX_DEPTH = 64;

    private ServerListReader() {
    }

    public static List<ServerEntry> read(Path serversDat) {
        if (!Files.isRegularFile(serversDat)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(serversDat)) {
            return read(in);
        } catch (Exception e) {
            PrelaunchLog.error("Failed to read " + serversDat, e);
            return List.of();
        }
    }

    static List<ServerEntry> read(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(raw));
        int rootType = in.readUnsignedByte();
        if (rootType != TAG_COMPOUND) {
            return List.of();
        }
        in.readUTF();
        Object root = readPayload(in, TAG_COMPOUND, 0);
        List<ServerEntry> result = new ArrayList<>();
        if (root instanceof Map<?, ?> map && map.get("servers") instanceof List<?> servers) {
            for (Object server : servers) {
                if (server instanceof Map<?, ?> entry && entry.get("ip") instanceof String ip && !ip.isBlank()) {
                    Object name = entry.get("name");
                    String title = name instanceof String s && !s.isBlank() ? s.replaceAll("§.", "") : ip;
                    result.add(new ServerEntry(title, ip.trim()));
                }
            }
        }
        return result;
    }

    private static Object readPayload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested too deep");
        }
        switch (type) {
            case TAG_BYTE:
                return in.readByte();
            case TAG_SHORT:
                return in.readShort();
            case TAG_INT:
                return in.readInt();
            case TAG_LONG:
                return in.readLong();
            case TAG_FLOAT:
                return in.readFloat();
            case TAG_DOUBLE:
                return in.readDouble();
            case TAG_BYTE_ARRAY:
                skipFully(in, checkedLength(in.readInt()));
                return null;
            case TAG_STRING:
                return in.readUTF();
            case TAG_LIST: {
                int elementType = in.readUnsignedByte();
                int length = checkedLength(in.readInt());
                List<Object> list = new ArrayList<>();
                for (int i = 0; i < length; i++) {
                    list.add(readPayload(in, elementType, depth + 1));
                }
                return list;
            }
            case TAG_COMPOUND: {
                Map<String, Object> map = new LinkedHashMap<>();
                while (true) {
                    int childType = in.readUnsignedByte();
                    if (childType == TAG_END) {
                        return map;
                    }
                    String key = in.readUTF();
                    map.put(key, readPayload(in, childType, depth + 1));
                }
            }
            case TAG_INT_ARRAY:
                skipFully(in, (long) checkedLength(in.readInt()) * 4);
                return null;
            case TAG_LONG_ARRAY:
                skipFully(in, (long) checkedLength(in.readInt()) * 8);
                return null;
            case TAG_END:
                return null;
            default:
                throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static int checkedLength(int length) throws IOException {
        if (length < 0) {
            throw new IOException("Negative NBT length");
        }
        return length;
    }

    private static void skipFully(DataInputStream in, long bytes) throws IOException {
        while (bytes > 0) {
            long skipped = in.skip(bytes);
            if (skipped <= 0) {
                in.readByte();
                skipped = 1;
            }
            bytes -= skipped;
        }
    }
}
