package com.habitrain.lottery.prelaunch.late;

import static org.junit.jupiter.api.Assertions.*;

import com.habitrain.lottery.prelaunch.ServerAddressParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for Audit I-18: RFC 2782 {@code "."} unavailable SRV targets and invalid
 * ports must never resolve to an empty host or port 0.
 */
class SrvResolverRfc2782DotTargetTest {

    @Test
    void rejectsRfc2782DotTargetEmptyTargetsAndInvalidPorts() {
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 0 0 ."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 25565 ."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 25565 .."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 0 play.example.com."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 -1 play.example.com."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 65536 play.example.com."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 abc play.example.com."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 25565"));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("0 5 25565 bad target.example.com."));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord(null));
        assertEquals(Optional.empty(), SrvResolver.parseSrvRecord("   "));
    }

    @Test
    void parsesValidSrvRecordsAndSkipsNumericOrIpv6HostsWithoutDnsQuery() {
        assertEquals(
                Optional.of(new ServerAddressParser.HostPort("play.example.com", 25570, true)),
                SrvResolver.parseSrvRecord("0 5 25570 play.example.com.")
        );
        assertEquals(
                Optional.of(new ServerAddressParser.HostPort("node1.example.net", 25565, true)),
                SrvResolver.parseSrvRecord("10 20 25565 node1.example.net")
        );

        assertEquals(Optional.empty(), SrvResolver.resolve(null));
        assertEquals(Optional.empty(), SrvResolver.resolve(""));
        assertEquals(Optional.empty(), SrvResolver.resolve("127.0.0.1"));
        assertEquals(Optional.empty(), SrvResolver.resolve("[::1]"));
    }
}
