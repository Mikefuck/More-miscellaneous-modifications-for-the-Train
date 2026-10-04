package com.habitrain.lottery.prelaunch.late;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class McLoginClientTest {

    private static final McLoginClient.Account ACCOUNT =
            new McLoginClient.Account("Mike5321", UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5"), "");

    @Test
    void serverHashMatchesMinecraftVectors() throws Exception {
        // Known vectors for Minecraft's signed hex digest.
        assertEquals("4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48", signedHex("Notch"));
        assertEquals("-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1", signedHex("jeb_"));
        assertEquals("88e16a1019277b15d58faf0541e11910eb756f6", signedHex("simon"));
    }

    private static String signedHex(String text) {
        return McLoginClient.serverHash(text, new byte[0], new byte[0]);
    }

    @Test
    void offlineLoginWithCompressionAndForeignQueries() throws Exception {
        List<String> seen = new ArrayList<>();
        McLoginClient.AutoModpackData data = runAgainst(server -> {
            McLoginClient.Connection conn = new McLoginClient.Connection(server.getInputStream(), server.getOutputStream());
            McLoginClient.Packet handshake = conn.read();
            assertEquals(0x00, handshake.id());
            assertEquals(McLoginClient.PROTOCOL_1_21_1, handshake.in().readVarInt());
            assertEquals("localhost", handshake.in().readString());
            McLoginClient.Packet hello = conn.read();
            assertEquals("Mike5321", hello.in().readString());

            conn.send(0x03, out -> out.writeVarInt(16));
            conn.threshold = 16;
            conn.send(0x05, out -> out.writeString("some:cookie"));
            assertCookieResponse(conn.read());
            // A query from an unrelated mod must be answered "not understood".
            conn.send(0x04, out -> {
                out.writeVarInt(7);
                out.writeString("othermod:hello");
                out.write(new byte[] {1, 2, 3});
            });
            McLoginClient.Packet foreign = conn.read();
            assertEquals(0x02, foreign.id());
            assertEquals(7, foreign.in().readVarInt());
            assertFalse(foreign.in().readBoolean());

            serveAutoModpack(conn, seen);
            return null;
        });
        assertEquals("", data.address());
        assertEquals(-1, data.port());
        assertEquals("habitrain", data.modpackName());
        assertEquals("c2VjcmV0", data.secret());
        assertEquals(1_700_000_000L, data.timestamp());
        assertTrue(data.requiresMagic());
        assertEquals(List.of("{\"loaders\":[\"fabric\"],\"amVersion\":\"4.0.6\",\"mcVersion\":\"1.21.1\"}", "true"), seen);
    }

    @Test
    void encryptedLoginWithoutSessionAuthentication() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keys = generator.generateKeyPair();
        byte[] token = {9, 8, 7, 6};
        List<String> seen = new ArrayList<>();
        McLoginClient.AutoModpackData data = runAgainst(server -> {
            McLoginClient.Connection conn = new McLoginClient.Connection(server.getInputStream(), server.getOutputStream());
            conn.read();
            conn.read();
            conn.send(0x01, out -> {
                out.writeString("");
                out.writeByteArray(keys.getPublic().getEncoded());
                out.writeByteArray(token);
                out.writeBoolean(false);
            });
            McLoginClient.Packet response = conn.read();
            assertEquals(0x01, response.id());
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.DECRYPT_MODE, keys.getPrivate());
            byte[] secret = rsa.doFinal(response.in().readByteArray());
            rsa.init(Cipher.DECRYPT_MODE, keys.getPrivate());
            assertEquals(new String(token, StandardCharsets.ISO_8859_1),
                    new String(rsa.doFinal(response.in().readByteArray()), StandardCharsets.ISO_8859_1));
            conn.enableEncryption(new SecretKeySpec(secret, "AES"));
            serveAutoModpack(conn, seen);
            return null;
        });
        assertEquals("c2VjcmV0", data.secret());
        assertEquals(2, seen.size());
    }

    @Test
    void reportsServerWithoutAutoModpack() {
        McLoginClient.LoginException error = assertThrows(McLoginClient.LoginException.class, () -> runAgainst(server -> {
            McLoginClient.Connection conn = new McLoginClient.Connection(server.getInputStream(), server.getOutputStream());
            conn.read();
            conn.read();
            conn.send(0x00, out -> out.writeString("{\"text\":\"You are not white-listed on this server!\"}"));
            return null;
        }));
        assertTrue(error.getMessage().contains("You are not white-listed on this server!"));
    }

    private static void assertCookieResponse(McLoginClient.Packet packet) throws IOException {
        assertEquals(0x04, packet.id());
        assertEquals("some:cookie", packet.in().readString());
        assertFalse(packet.in().readBoolean());
    }

    /** Sends AutoModpack's two login queries the way its server side does and records the answers. */
    private static void serveAutoModpack(McLoginClient.Connection conn, List<String> seen) throws IOException {
        conn.send(0x04, out -> {
            out.writeVarInt(-100);
            out.writeString(McLoginClient.HANDSHAKE_CHANNEL);
            out.writeString("{\"loaders\":[\"fabric\"],\"amVersion\":\"4.0.6\",\"mcVersion\":\"1.21.1\"}");
        });
        McLoginClient.Packet handshake = conn.read();
        assertEquals(0x02, handshake.id());
        assertEquals(-100, handshake.in().readVarInt());
        assertTrue(handshake.in().readBoolean());
        seen.add(handshake.in().readString());

        conn.send(0x04, out -> {
            out.writeVarInt(-101);
            out.writeString(McLoginClient.DATA_CHANNEL);
            out.writeString("{\"address\":\"\",\"port\":-1,\"modpackName\":\"habitrain\",\"secret\":{\"secret\":\"c2VjcmV0\","
                    + "\"timestamp\":1700000000},\"modRequired\":true,\"requiresMagic\":true}");
        });
        McLoginClient.Packet data = conn.read();
        assertEquals(-101, data.in().readVarInt());
        assertTrue(data.in().readBoolean());
        seen.add(data.in().readString());
    }

    interface ServerScript {
        Void run(Socket socket) throws Exception;
    }

    private static McLoginClient.AutoModpackData runAgainst(ServerScript script) throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            CompletableFuture<Void> server = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(10_000);
                    return script.run(socket);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            McLoginClient client = new McLoginClient(ACCOUNT, "http://unused.invalid", v -> v, "1.21.1");
            McLoginClient.AutoModpackData result;
            try {
                result = client.fetch("localhost", listener.getLocalPort());
            } catch (Exception clientError) {
                // Report the client's failure rather than the server script timing out because of it.
                try {
                    server.get(10, TimeUnit.SECONDS);
                } catch (Exception serverError) {
                    clientError.addSuppressed(serverError);
                }
                throw clientError;
            }
            server.get(10, TimeUnit.SECONDS);
            return result;
        }
    }
}
