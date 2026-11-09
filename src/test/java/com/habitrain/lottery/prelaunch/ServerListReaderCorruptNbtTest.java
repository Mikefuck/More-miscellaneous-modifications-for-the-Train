package com.habitrain.lottery.prelaunch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression coverage for Audit I-18: corrupt {@code servers.dat} NBT must fail fast with
 * {@link IOException} (and degrade to an empty list on disk) without OOM or unbounded loops.
 */
class ServerListReaderCorruptNbtTest {

    @TempDir
    Path temp;

    @Test
    void readsValidServersDatAndAllowsEmptyTagEndList() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(10); // root TAG_COMPOUND
            out.writeUTF("");

            // An empty TAG_LIST of TAG_END (0, 0) is valid vanilla NBT.
            out.writeByte(9);
            out.writeUTF("emptyList");
            out.writeByte(0); // TAG_END
            out.writeInt(0);

            // "servers" list with one entry
            out.writeByte(9);
            out.writeUTF("servers");
            out.writeByte(10); // TAG_COMPOUND
            out.writeInt(1);

            out.writeByte(8);
            out.writeUTF("name");
            out.writeUTF("§a哈比列车 §b测试服");
            out.writeByte(8);
            out.writeUTF("ip");
            out.writeUTF("  play.example.com:25565  ");
            out.writeByte(0); // end server compound

            out.writeByte(0); // end root compound
        }

        List<ServerListReader.ServerEntry> entries =
                ServerListReader.read(new ByteArrayInputStream(raw.toByteArray()));
        assertEquals(List.of(new ServerListReader.ServerEntry("哈比列车 测试服", "play.example.com:25565")), entries);
    }

    @Test
    void rejectsNonEmptyTagEndListWithoutAllocatingHugeList() throws IOException {
        byte[] maxIntTagEndList = buildRootWithSingleList(0, Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> ServerListReader.read(new ByteArrayInputStream(maxIntTagEndList)));

        byte[] smallNonEmptyTagEndList = buildRootWithSingleList(0, 3);
        assertThrows(IOException.class, () -> ServerListReader.read(new ByteArrayInputStream(smallNonEmptyTagEndList)));

        Path corruptFile = temp.resolve("servers.dat");
        Files.write(corruptFile, maxIntTagEndList);
        assertEquals(List.of(), ServerListReader.read(corruptFile));
    }

    @Test
    void rejectsNegativeOversizedOrUnknownTypeLists() throws IOException {
        byte[] negativeLen = buildRootWithSingleList(8, -1);
        assertThrows(IOException.class, () -> ServerListReader.read(new ByteArrayInputStream(negativeLen)));

        byte[] overLimitLen = buildRootWithSingleList(8, ServerListReader.MAX_LIST_LENGTH + 1);
        assertThrows(IOException.class, () -> ServerListReader.read(new ByteArrayInputStream(overLimitLen)));

        byte[] unknownElemType = buildRootWithSingleList(99, 1);
        assertThrows(IOException.class, () -> ServerListReader.read(new ByteArrayInputStream(unknownElemType)));
    }

    @Test
    void rejectsDeeplyNestedCompoundsAndOversizedPrimitiveArrays() throws IOException {
        ByteArrayOutputStream nested = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(nested)) {
            out.writeByte(10);
            out.writeUTF("");
            for (int i = 0; i < 70; i++) {
                out.writeByte(10);
                out.writeUTF("c" + i);
            }
            for (int i = 0; i <= 70; i++) {
                out.writeByte(0);
            }
        }
        assertThrows(IOException.class, () -> ServerListReader.read(new ByteArrayInputStream(nested.toByteArray())));

        ByteArrayOutputStream hugeLongArray = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(hugeLongArray)) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(12); // TAG_LONG_ARRAY
            out.writeUTF("arr");
            out.writeInt(4_000_000); // 32 MB > 16 MB limit
            out.writeByte(0);
        }
        assertThrows(IOException.class,
                () -> ServerListReader.read(new ByteArrayInputStream(hugeLongArray.toByteArray())));
    }

    private static byte[] buildRootWithSingleList(int elementType, int length) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(10); // root TAG_COMPOUND
            out.writeUTF("");
            out.writeByte(9); // TAG_LIST
            out.writeUTF("servers");
            out.writeByte(elementType);
            out.writeInt(length);
            out.writeByte(0);
        }
        return raw.toByteArray();
    }
}
