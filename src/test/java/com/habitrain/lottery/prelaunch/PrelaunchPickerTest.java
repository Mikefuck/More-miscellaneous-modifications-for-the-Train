package com.habitrain.lottery.prelaunch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrelaunchPickerTest {

    @Test
    void parsesAddressesLikeVanillaAndAutoModpack() {
        ServerAddressParser.HostPort plain = ServerAddressParser.parse("Play.Example.com");
        assertEquals("play.example.com:25565", plain.normalized());
        assertFalse(plain.explicitPort());

        ServerAddressParser.HostPort withPort = ServerAddressParser.parse(" play.example.com.:30000 ");
        assertEquals("play.example.com:30000", withPort.normalized());
        assertTrue(withPort.explicitPort());

        assertEquals("::1:25565", ServerAddressParser.parse("[::1]").normalized());
        assertEquals("::1:25570", ServerAddressParser.parse("[::1]:25570").normalized());
        assertEquals("play.example.com:30000", ServerAddressParser.normalizeStored("Play.example.com:30000"));
        assertEquals("::1:25570", ServerAddressParser.normalizeStored("::1:25570"));
    }

    @Test
    void readsServersDat() throws IOException {
        List<ServerListReader.ServerEntry> servers = ServerListReader.read(new ByteArrayInputStream(
                serversDat(new String[][] {{"§a哈比列车", "train.example.com"}, {"", "other.example.com:30000"}})));
        assertEquals(2, servers.size());
        assertEquals("哈比列车", servers.get(0).name());
        assertEquals("train.example.com", servers.get(0).ip());
        assertEquals("other.example.com:30000", servers.get(1).name());
    }

    private static byte[] serversDat(String[][] servers) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10);
        out.writeUTF("");
        out.writeByte(9);
        out.writeUTF("servers");
        out.writeByte(10);
        out.writeInt(servers.length);
        for (String[] server : servers) {
            out.writeByte(8);
            out.writeUTF("name");
            out.writeUTF(server[0]);
            out.writeByte(8);
            out.writeUTF("ip");
            out.writeUTF(server[1]);
            out.writeByte(7);
            out.writeUTF("icon_bytes");
            out.writeInt(3);
            out.write(new byte[] {1, 2, 3});
            out.writeByte(1);
            out.writeUTF("acceptTextures");
            out.writeByte(1);
            out.writeByte(0);
        }
        out.writeByte(0);
        return bytes.toByteArray();
    }
}
