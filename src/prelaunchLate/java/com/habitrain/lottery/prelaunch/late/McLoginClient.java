package com.habitrain.lottery.prelaunch.late;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Minimal Minecraft 1.21.1 login-phase client, used before the game starts to obtain the
 * AutoModpack download secret exactly like a real join does: the server authenticates the
 * player (Mojang session for online-mode servers), sends AutoModpack's {@code automodpack:handshake}
 * and {@code automodpack:data} login queries, and we answer "modpack needs update", after which the
 * server disconnects us. The access token only ever goes to the session server, never to the
 * Minecraft server.
 */
final class McLoginClient {

    static final int PROTOCOL_1_21_1 = 767;
    static final String HANDSHAKE_CHANNEL = "automodpack:handshake";
    static final String DATA_CHANNEL = "automodpack:data";
    private static final int MAX_PACKET = 2 * 1024 * 1024;
    private static final Gson GSON = new Gson();

    record Account(String name, UUID uuid, String accessToken) {
    }

    /** Contents of AutoModpack's {@code DataPacket}. */
    record AutoModpackData(String address, int port, String modpackName, String secret, long timestamp,
                           boolean requiresMagic) {
    }

    /** Failure with a message meant for the player. */
    static final class LoginException extends IOException {
        LoginException(String message) {
            super(message);
        }

        LoginException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Account account;
    private final String sessionHost;
    private final UnaryOperator<String> handshakeVersion;
    private final String mcVersion;

    /**
     * @param handshakeVersion maps the server's AutoModpack version to the version we answer with
     */
    McLoginClient(Account account, String sessionHost, UnaryOperator<String> handshakeVersion, String mcVersion) {
        this.account = account;
        this.sessionHost = sessionHost;
        this.handshakeVersion = handshakeVersion;
        this.mcVersion = mcVersion;
    }

    AutoModpackData fetch(String host, int port) throws IOException {
        try (Socket socket = new Socket()) {
            try {
                socket.connect(new InetSocketAddress(host, port), 10_000);
            } catch (IOException e) {
                throw new LoginException("无法连接服务器 " + host + ":" + port + "（" + e.getMessage() + "）", e);
            }
            socket.setSoTimeout(20_000);
            return fetch(socket.getInputStream(), socket.getOutputStream(), host, port);
        }
    }

    AutoModpackData fetch(InputStream rawIn, OutputStream rawOut, String host, int port) throws IOException {
        Connection connection = new Connection(rawIn, rawOut);
        connection.send(0x00, out -> {
            out.writeVarInt(PROTOCOL_1_21_1);
            out.writeString(host);
            out.writeShort(port);
            out.writeVarInt(2);
        });
        connection.send(0x00, out -> {
            out.writeString(account.name());
            out.writeLong(account.uuid().getMostSignificantBits());
            out.writeLong(account.uuid().getLeastSignificantBits());
        });
        while (true) {
            Packet packet = connection.read();
            switch (packet.id) {
                case 0x00 -> throw new LoginException("服务器断开了连接：" + componentText(packet.in.readString()));
                case 0x01 -> handleEncryption(connection, packet.in);
                case 0x02 -> throw new LoginException(
                        "服务器在登录时没有下发 AutoModpack 整合包信息（服务器未安装 AutoModpack 或其下载服务未运行）。");
                case 0x03 -> connection.threshold = packet.in.readVarInt();
                case 0x04 -> {
                    AutoModpackData data = handleQuery(connection, packet.in);
                    if (data != null) {
                        return data;
                    }
                }
                case 0x05 -> {
                    String key = packet.in.readString();
                    connection.send(0x04, out -> {
                        out.writeString(key);
                        out.writeBoolean(false);
                    });
                }
                default -> throw new LoginException("服务器发送了未知的登录数据包 0x" + Integer.toHexString(packet.id)
                        + "（服务器版本可能不是 1.21.1）。");
            }
        }
    }

    private AutoModpackData handleQuery(Connection connection, PacketReader in) throws IOException {
        int queryId = in.readVarInt();
        String channel = in.readString();
        if (HANDSHAKE_CHANNEL.equals(channel)) {
            JsonObject request = JsonParser.parseString(in.readString()).getAsJsonObject();
            String serverVersion = request.has("amVersion") ? request.get("amVersion").getAsString() : "";
            JsonObject response = new JsonObject();
            JsonArray loaders = new JsonArray();
            loaders.add("fabric");
            response.add("loaders", loaders);
            response.addProperty("amVersion", handshakeVersion.apply(serverVersion));
            response.addProperty("mcVersion", mcVersion);
            String json = GSON.toJson(response);
            connection.send(0x02, out -> {
                out.writeVarInt(queryId);
                out.writeBoolean(true);
                out.writeString(json);
            });
            return null;
        }
        if (DATA_CHANNEL.equals(channel)) {
            JsonObject data = JsonParser.parseString(in.readString()).getAsJsonObject();
            JsonObject secret = data.getAsJsonObject("secret");
            AutoModpackData result = new AutoModpackData(
                    stringOr(data, "address", ""),
                    data.has("port") ? data.get("port").getAsInt() : -1,
                    stringOr(data, "modpackName", ""),
                    secret == null ? "" : stringOr(secret, "secret", ""),
                    secret != null && secret.has("timestamp") ? secret.get("timestamp").getAsLong() : 0L,
                    data.has("requiresMagic") && data.get("requiresMagic").getAsBoolean());
            if (result.secret().isBlank()) {
                throw new LoginException("服务器下发的 AutoModpack 凭证为空。");
            }
            // "true" = the client still has to install/update the modpack; the server then disconnects us.
            connection.send(0x02, out -> {
                out.writeVarInt(queryId);
                out.writeBoolean(true);
                out.writeString("true");
            });
            return result;
        }
        // Some other mod's login query: answer "not understood" like a client without that mod.
        connection.send(0x02, out -> {
            out.writeVarInt(queryId);
            out.writeBoolean(false);
        });
        return null;
    }

    private void handleEncryption(Connection connection, PacketReader in) throws IOException {
        String serverId = in.readString();
        byte[] publicKeyBytes = in.readByteArray();
        byte[] verifyToken = in.readByteArray();
        boolean shouldAuthenticate = in.readBoolean();
        try {
            PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(publicKeyBytes));
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(128);
            SecretKey sharedSecret = generator.generateKey();
            if (shouldAuthenticate) {
                joinSession(serverHash(serverId, sharedSecret.getEncoded(), publicKey.getEncoded()));
            }
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encryptedSecret = rsa.doFinal(sharedSecret.getEncoded());
            rsa.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encryptedToken = rsa.doFinal(verifyToken);
            connection.send(0x01, out -> {
                out.writeByteArray(encryptedSecret);
                out.writeByteArray(encryptedToken);
            });
            connection.enableEncryption(sharedSecret);
        } catch (GeneralSecurityException e) {
            throw new LoginException("与服务器建立加密连接失败：" + e.getMessage(), e);
        }
    }

    private void joinSession(String serverHash) throws IOException {
        String token = account.accessToken();
        if (token == null || token.isBlank() || token.length() < 20) {
            throw new LoginException("该服务器开启了正版验证，但当前启动器没有提供正版登录凭据（离线账号无法在启动前登录）。");
        }
        JsonObject body = new JsonObject();
        body.addProperty("accessToken", token);
        body.addProperty("selectedProfile", account.uuid().toString().replace("-", ""));
        body.addProperty("serverId", serverHash);
        HttpRequest request = HttpRequest.newBuilder(URI.create(sessionHost + "/session/minecraft/join"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LoginException("正版验证被中断。", e);
        } catch (IOException e) {
            throw new LoginException("无法连接正版验证服务器（" + e.getMessage() + "）。", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new LoginException("正版验证失败（HTTP " + response.statusCode() + "），请确认启动器已登录正版账号且登录未过期。");
        }
    }

    /** Minecraft's signed hex SHA-1 used as the session {@code serverId}. */
    static String serverHash(String serverId, byte[] sharedSecret, byte[] publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            digest.update(sharedSecret);
            digest.update(publicKey);
            return new BigInteger(digest.digest()).toString(16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Flattens a JSON text component into plain text for error messages. */
    static String componentText(String json) {
        try {
            StringBuilder out = new StringBuilder();
            appendText(JsonParser.parseString(json), out);
            return out.length() == 0 ? json : out.toString();
        } catch (RuntimeException e) {
            return json;
        }
    }

    private static void appendText(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            out.append(element.getAsString());
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> appendText(child, out));
        } else if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("text")) {
                out.append(object.get("text").getAsString());
            } else if (object.has("translate")) {
                out.append(object.get("translate").getAsString());
            }
            appendText(object.get("extra"), out);
        }
    }

    private static String stringOr(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    // ---- framing ----

    interface PacketBody {
        void write(PacketWriter out) throws IOException;
    }

    record Packet(int id, PacketReader in) {
    }

    static final class Connection {
        private InputStream in;
        private OutputStream out;
        int threshold = -1;

        Connection(InputStream rawIn, OutputStream rawOut) {
            this.in = new BufferedInputStream(rawIn);
            this.out = new BufferedOutputStream(rawOut);
        }

        void enableEncryption(SecretKey key) throws GeneralSecurityException {
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(key.getEncoded()));
            in = new CipherInputStream(in, decrypt);
            out = new CipherOutputStream(out, encrypt);
        }

        void send(int id, PacketBody body) throws IOException {
            PacketWriter writer = new PacketWriter();
            writer.writeVarInt(id);
            body.write(writer);
            byte[] data = writer.toByteArray();
            PacketWriter frame = new PacketWriter();
            if (threshold >= 0) {
                PacketWriter inner = new PacketWriter();
                if (data.length >= threshold) {
                    inner.writeVarInt(data.length);
                    inner.write(deflate(data));
                } else {
                    inner.writeVarInt(0);
                    inner.write(data);
                }
                data = inner.toByteArray();
            }
            frame.writeVarInt(data.length);
            frame.write(data);
            out.write(frame.toByteArray());
            out.flush();
        }

        Packet read() throws IOException {
            int length = readVarInt(in);
            if (length <= 0 || length > MAX_PACKET) {
                throw new IOException("Bad packet length " + length);
            }
            // Not readNBytes(): it issues a final zero-length read, and CipherInputStream blocks on
            // that waiting for more ciphertext.
            byte[] frame = new byte[length];
            try {
                new DataInputStream(in).readFully(frame);
            } catch (EOFException e) {
                throw new EOFException("服务器提前关闭了连接");
            }
            PacketReader reader = new PacketReader(frame);
            if (threshold >= 0) {
                int dataLength = reader.readVarInt();
                if (dataLength != 0) {
                    if (dataLength > MAX_PACKET) {
                        throw new IOException("Bad uncompressed length " + dataLength);
                    }
                    reader = new PacketReader(inflate(reader.readRemaining(), dataLength));
                }
            }
            return new Packet(reader.readVarInt(), reader);
        }
    }

    static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    static byte[] inflate(byte[] data, int expectedLength) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] result = new byte[expectedLength];
        try {
            int total = 0;
            while (total < expectedLength && !inflater.finished()) {
                int n = inflater.inflate(result, total, expectedLength - total);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                total += n;
            }
            if (total != expectedLength) {
                throw new IOException("Bad compressed packet");
            }
            return result;
        } catch (DataFormatException e) {
            throw new IOException("Bad compressed packet", e);
        } finally {
            inflater.end();
        }
    }

    static int readVarInt(InputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("服务器关闭了连接");
            }
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("VarInt too big");
    }

    static final class PacketWriter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        void writeVarInt(int value) {
            while ((value & ~0x7F) != 0) {
                bytes.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            bytes.write(value);
        }

        void writeString(String value) {
            byte[] data = value.getBytes(StandardCharsets.UTF_8);
            writeVarInt(data.length);
            write(data);
        }

        void writeByteArray(byte[] data) {
            writeVarInt(data.length);
            write(data);
        }

        void writeShort(int value) {
            bytes.write((value >>> 8) & 0xFF);
            bytes.write(value & 0xFF);
        }

        void writeLong(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) {
                bytes.write((int) (value >>> shift) & 0xFF);
            }
        }

        void writeBoolean(boolean value) {
            bytes.write(value ? 1 : 0);
        }

        void write(byte[] data) {
            bytes.write(data, 0, data.length);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }

    static final class PacketReader {
        private final DataInputStream in;

        PacketReader(byte[] data) {
            this.in = new DataInputStream(new ByteArrayInputStream(data));
        }

        int readVarInt() throws IOException {
            return McLoginClient.readVarInt(in);
        }

        String readString() throws IOException {
            return new String(readByteArray(), StandardCharsets.UTF_8);
        }

        byte[] readByteArray() throws IOException {
            int length = readVarInt();
            if (length < 0 || length > MAX_PACKET) {
                throw new IOException("Bad array length " + length);
            }
            byte[] data = in.readNBytes(length);
            if (data.length != length) {
                throw new EOFException("Truncated packet");
            }
            return data;
        }

        boolean readBoolean() throws IOException {
            return in.readBoolean();
        }

        long readLong() throws IOException {
            return in.readLong();
        }

        int readUnsignedShort() throws IOException {
            return in.readUnsignedShort();
        }

        byte[] readRemaining() throws IOException {
            return in.readAllBytes();
        }
    }
}
