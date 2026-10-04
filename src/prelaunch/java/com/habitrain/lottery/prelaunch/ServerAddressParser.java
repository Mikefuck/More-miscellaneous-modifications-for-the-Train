package com.habitrain.lottery.prelaunch;

import java.util.Locale;

/** Parses a multiplayer-list address the way vanilla {@code ServerAddress.parseString} does. */
public final class ServerAddressParser {

    public static final int DEFAULT_PORT = 25565;

    /**
     * @param explicitPort whether the user typed a port (vanilla only looks up SRV records otherwise)
     */
    public record HostPort(String host, int port, boolean explicitPort) {

        /** {@code host:port} the way AutoModpack stores {@code serverAddress}. */
        public String normalized() {
            String h = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
            return h.toLowerCase(Locale.ROOT) + ":" + port;
        }
    }

    private ServerAddressParser() {
    }

    public static HostPort parse(String raw) {
        String address = raw == null ? "" : raw.trim();
        if (address.startsWith("[")) {
            int close = address.indexOf(']');
            if (close > 0) {
                String host = address.substring(1, close);
                String rest = address.substring(close + 1);
                if (rest.startsWith(":")) {
                    Integer port = parsePort(rest.substring(1));
                    if (port != null) {
                        return new HostPort(host, port, true);
                    }
                }
                return new HostPort(host, DEFAULT_PORT, false);
            }
        }
        int colon = address.lastIndexOf(':');
        // A single colon separates host and port; more colons mean a bare IPv6 address.
        if (colon > 0 && address.indexOf(':') == colon) {
            Integer port = parsePort(address.substring(colon + 1));
            if (port != null) {
                return new HostPort(address.substring(0, colon), port, true);
            }
            return new HostPort(address.substring(0, colon), DEFAULT_PORT, false);
        }
        return new HostPort(address, DEFAULT_PORT, false);
    }

    /** Normalizes an address already stored by AutoModpack as {@code host:port}. */
    public static String normalizeStored(String stored) {
        if (stored == null) {
            return "";
        }
        int colon = stored.lastIndexOf(':');
        if (colon > 0) {
            Integer port = parsePort(stored.substring(colon + 1));
            if (port != null) {
                return new HostPort(stored.substring(0, colon), port, true).normalized();
            }
        }
        return parse(stored).normalized();
    }

    private static Integer parsePort(String text) {
        if (!text.trim().matches("\\d{1,5}")) {
            return null;
        }
        int port = Integer.parseInt(text.trim());
        return port > 0 && port <= 65535 ? port : null;
    }
}
