package com.habitrain.lottery.prelaunch.late;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Regression coverage for Audit B-19 & I-20: malformed VarInt/compression lengths, deeply nested
 * disconnect JSON, unbounded login packet floods, overall deadlines, and user cancellation.
 */
class McLoginClientMalformedPacketTest {

    private static McLoginClient newClient() {
        return new McLoginClient(
                new McLoginClient.Account("Tester", UUID.randomUUID(), ""),
                "http://127.0.0.1:1",
                UnaryOperator.identity(),
                "1.21.1"
        );
    }

    @Test
    void negativeOrOversizedCompressedDataLengthThrowsIoException() {
        for (int invalidDataLen : new int[] {-1, -100, McLoginClient.MAX_PACKET + 1}) {
            McLoginClient.PacketWriter body = new McLoginClient.PacketWriter();
            body.writeVarInt(invalidDataLen);
            body.write(new byte[] {1, 2, 3, 4});
            byte[] inner = body.toByteArray();

            McLoginClient.PacketWriter frame = new McLoginClient.PacketWriter();
            frame.writeVarInt(inner.length);
            frame.write(inner);

            McLoginClient.Connection conn = new McLoginClient.Connection(
                    new ByteArrayInputStream(frame.toByteArray()),
                    new ByteArrayOutputStream()
            );
            conn.threshold = 256;
            IOException ex = assertThrows(IOException.class, conn::read);
            assertTrue(ex.getMessage().contains("Bad uncompressed length"));
        }
    }

    @Test
    void compressionThresholdPacketBelowMinusOneThrowsIoException() {
        McLoginClient.PacketWriter serverStream = new McLoginClient.PacketWriter();
        writeUncompressedPacket(serverStream, 0x03, out -> out.writeVarInt(-2));

        IOException ex = assertThrows(IOException.class, () -> newClient().fetch(
                new ByteArrayInputStream(serverStream.toByteArray()),
                new ByteArrayOutputStream(),
                "localhost",
                25565
        ));
        assertTrue(ex.getMessage().contains("Bad compression threshold"));
    }

    @Test
    void deeplyNestedAndHugeDisconnectJsonIsBoundedAndSurrogateSafe() {
        StringBuilder deep = new StringBuilder();
        int depth = 220;
        for (int i = 0; i < depth; i++) {
            deep.append("{\"text\":\"x\",\"extra\":[");
        }
        deep.append("\"leaf\"");
        for (int i = 0; i < depth; i++) {
            deep.append("]}");
        }

        String flattened = assertDoesNotThrow(() -> McLoginClient.componentText(deep.toString()));
        assertFalse(flattened.isEmpty());
        assertTrue(flattened.length() <= McLoginClient.MAX_TEXT_DEPTH + 2,
                "Depth cap must stop recursion well before 220 levels: got length " + flattened.length());

        // Construct a string with an emoji ("\uD83D\uDE00") straddling MAX_TEXT_LENGTH - 1 (index 4095).
        String prefix = "a".repeat(McLoginClient.MAX_TEXT_LENGTH - 1);
        String hugeJson = "{\"text\":\"" + prefix + "\uD83D\uDE00trailing\"}";
        String truncated = McLoginClient.componentText(hugeJson);
        assertEquals(McLoginClient.MAX_TEXT_LENGTH - 1, truncated.length(),
                "Must not leave an unpaired high surrogate at index MAX_TEXT_LENGTH - 1");
        assertFalse(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1)));
    }

    @Test
    void excessiveLoginPacketsAbortWithLoginException() {
        McLoginClient.PacketWriter serverStream = new McLoginClient.PacketWriter();
        for (int i = 0; i <= McLoginClient.MAX_LOGIN_PACKETS + 2; i++) {
            int queryId = i;
            writeUncompressedPacket(serverStream, 0x04, out -> {
                out.writeVarInt(queryId);
                out.writeString("other_mod:noop_ping");
                out.write(new byte[] {0});
            });
        }

        assertThrows(McLoginClient.LoginException.class, () -> newClient().fetch(
                new ByteArrayInputStream(serverStream.toByteArray()),
                new ByteArrayOutputStream(),
                "localhost",
                25565
        ));
    }

    @Test
    @Timeout(10)
    void overallDeadlineAndProgressWindowCancellationAbortStalledServer() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Thread acceptor = Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket clientSocket = server.accept();
                        // Hold connection open silently until closed by test teardown.
                        Thread.startVirtualThread(() -> {
                            try (clientSocket) {
                                clientSocket.getInputStream().readAllBytes();
                            } catch (IOException ignored) {
                            }
                        });
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });

            int port = server.getLocalPort();

            // 1. Short monotonic deadline (80 ms) aborts stalled server read quickly.
            assertThrows(McLoginClient.LoginException.class,
                    () -> newClient().fetch("127.0.0.1", port, null, 80L));

            // 2. ProgressWindow.cancel() closes the underlying socket and aborts immediately.
            ProgressWindow window = new ProgressWindow();
            Thread.startVirtualThread(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(60);
                } catch (InterruptedException ignored) {
                }
                window.cancel();
            });
            McLoginClient.LoginException cancelledEx = assertThrows(McLoginClient.LoginException.class,
                    () -> newClient().fetch("127.0.0.1", port, window, 10_000L));
            assertTrue(window.isCancelled());
            assertNotNull(cancelledEx.getMessage());
            acceptor.interrupt();
        }
    }

    @Test
    @Timeout(5)
    void deadlineExpiresInsideDripFedFrameAndItsLengthVarInt() {
        for (byte[] bytes : new byte[][] {
                {100, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
                {(byte) 0x80, (byte) 0x80, (byte) 0x80, 1}
        }) {
            var slow = new java.io.InputStream() {
                int index;
                @Override public int read() throws IOException {
                    try { Thread.sleep(35); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                    return index < bytes.length ? bytes[index++] & 255 : -1;
                }
                @Override public int read(byte[] target, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    int value = read();
                    if (value < 0) return -1;
                    target[offset] = (byte) value;
                    return 1;
                }
            };
            long start = System.nanoTime();
            var ex = assertThrows(McLoginClient.LoginException.class, () -> newClient().fetch(
                    slow, new ByteArrayOutputStream(), "localhost", 25565, 80, () -> false, timeout -> { }));
            assertTrue(ex.getMessage().contains("超时"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 300);
        }
    }

    private static void writeUncompressedPacket(McLoginClient.PacketWriter stream, int id,
                                                McLoginClient.PacketBody body) {
        try {
            McLoginClient.PacketWriter inner = new McLoginClient.PacketWriter();
            inner.writeVarInt(id);
            body.write(inner);
            byte[] payload = inner.toByteArray();
            stream.writeVarInt(payload.length);
            stream.write(payload);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
