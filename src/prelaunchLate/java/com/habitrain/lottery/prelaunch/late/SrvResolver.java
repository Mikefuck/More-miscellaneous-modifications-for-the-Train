package com.habitrain.lottery.prelaunch.late;

import com.habitrain.lottery.prelaunch.ServerAddressParser;
import java.util.Hashtable;
import java.util.Optional;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

/** {@code _minecraft._tcp} SRV lookup, the same JNDI query vanilla uses for addresses without port. */
final class SrvResolver {

    private SrvResolver() {
    }

    static Optional<ServerAddressParser.HostPort> resolve(String host) {
        if (host == null || host.isBlank() || host.matches("[0-9.]+") || host.contains(":")) {
            return Optional.empty();
        }
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            DirContext context = new InitialDirContext(env);
            try {
                Attributes attributes = context.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"});
                Attribute srv = attributes.get("srv");
                if (srv == null || srv.size() == 0) {
                    return Optional.empty();
                }
                for (int i = 0; i < srv.size(); i++) {
                    Object raw = srv.get(i);
                    if (raw == null) {
                        continue;
                    }
                    String text = raw.toString();
                    // RFC 2782: a "." target explicitly declares the service unavailable on this domain.
                    if (isServiceUnavailableRecord(text)) {
                        return Optional.empty();
                    }
                    Optional<ServerAddressParser.HostPort> parsed = parseSrvRecord(text);
                    if (parsed.isPresent()) {
                        return parsed;
                    }
                }
                return Optional.empty();
            } finally {
                context.close();
            }
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }

    private static boolean isServiceUnavailableRecord(String record) {
        if (record == null) {
            return false;
        }
        String[] parts = record.trim().split("\\s+", 4);
        return parts.length == 4 && ".".equals(parts[3].trim());
    }

    static Optional<ServerAddressParser.HostPort> parseSrvRecord(String record) {
        if (record == null || record.isBlank()) {
            return Optional.empty();
        }
        // "priority weight port target."
        String[] parts = record.trim().split("\\s+", 4);
        if (parts.length < 4) {
            return Optional.empty();
        }
        final int port;
        try {
            port = Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (port <= 0 || port > 65535) {
            return Optional.empty();
        }
        String rawTarget = parts[3].trim();
        if (rawTarget.isEmpty() || ".".equals(rawTarget)) {
            return Optional.empty();
        }
        String target = rawTarget.endsWith(".") ? rawTarget.substring(0, rawTarget.length() - 1).trim() : rawTarget;
        if (target.isEmpty() || ".".equals(target) || target.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        return Optional.of(new ServerAddressParser.HostPort(target, port, true));
    }
}
