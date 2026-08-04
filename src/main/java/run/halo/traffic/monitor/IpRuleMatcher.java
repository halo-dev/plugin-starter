package run.halo.traffic.monitor;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

final class IpRuleMatcher {
    private IpRuleMatcher() {}

    static boolean matches(String ip, String rules) {
        byte[] address = literalAddress(ip);
        if (address == null || rules == null || rules.isBlank()) {
            return false;
        }
        for (String rawRule : rules.split("[,;\\s]+")) {
            String rule = rawRule.trim();
            if (rule.isEmpty() || rule.startsWith("#")) {
                continue;
            }
            if (matchesRule(address, rule)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRule(byte[] address, String rule) {
        int slash = rule.indexOf('/');
        String literal = slash < 0 ? rule : rule.substring(0, slash);
        byte[] network = literalAddress(literal);
        if (network == null || network.length != address.length) {
            return false;
        }
        if (slash < 0) {
            return Arrays.equals(address, network);
        }
        int prefix;
        try {
            prefix = Integer.parseInt(rule.substring(slash + 1));
        } catch (NumberFormatException ignored) {
            return false;
        }
        if (prefix < 0 || prefix > address.length * 8) {
            return false;
        }
        int fullBytes = prefix / 8;
        int remainingBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (address[i] != network[i]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xff << (8 - remainingBits);
        return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    private static byte[] literalAddress(String value) {
        String normalized = ClientIpResolver.normalize(value);
        if (normalized == null || (!normalized.contains(".") && !normalized.contains(":"))) {
            return null;
        }
        int scope = normalized.indexOf('%');
        String withoutScope = scope >= 0 ? normalized.substring(0, scope) : normalized;
        try {
            return InetAddress.getByName(withoutScope).getAddress();
        } catch (UnknownHostException ignored) {
            return null;
        }
    }
}
