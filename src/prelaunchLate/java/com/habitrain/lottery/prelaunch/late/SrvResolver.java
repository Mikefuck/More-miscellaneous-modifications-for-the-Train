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
        if (host.isEmpty() || host.matches("[0-9.]+") || host.contains(":")) {
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
                // "priority weight port target."
                String[] parts = srv.get().toString().split(" ", 4);
                if (parts.length < 4) {
                    return Optional.empty();
                }
                String target = parts[3].endsWith(".") ? parts[3].substring(0, parts[3].length() - 1) : parts[3];
                return Optional.of(new ServerAddressParser.HostPort(target, Integer.parseInt(parts[2]), true));
            } finally {
                context.close();
            }
        } catch (Exception | LinkageError e) {
            return Optional.empty();
        }
    }
}
