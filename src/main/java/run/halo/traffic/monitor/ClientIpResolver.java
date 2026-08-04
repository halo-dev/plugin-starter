package run.halo.traffic.monitor;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;

final class ClientIpResolver {
    private static final Pattern FORWARDED_FOR =
        Pattern.compile("(?:^|[;,]\\s*)for=\\\"?([^;,\\\"]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile(
        "^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]+(?:%[0-9A-Za-z_.-]+)?$");

    private ClientIpResolver() {}

    static String resolve(ServerHttpRequest request, boolean trustProxyHeaders) {
        if (trustProxyHeaders) {
            String candidate = firstForwarded(request.getHeaders());
            String normalized = normalize(candidate);
            if (normalized != null) {
                return normalized;
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "unknown";
        }
        return remote.getAddress().getHostAddress();
    }

    private static String firstForwarded(HttpHeaders headers) {
        String forwarded = headers.getFirst("Forwarded");
        if (forwarded != null) {
            Matcher matcher = FORWARDED_FOR.matcher(forwarded);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        String xff = headers.getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",", 2)[0];
        }
        return headers.getFirst("X-Real-IP");
    }

    static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim().replace("\"", "");
        if (candidate.isBlank() || "unknown".equals(candidate.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (candidate.startsWith("[")) {
            int end = candidate.indexOf(']');
            candidate = end > 0 ? candidate.substring(1, end) : candidate;
        } else if (candidate.chars().filter(ch -> ch == ':').count() == 1
            && candidate.indexOf('.') >= 0) {
            candidate = candidate.substring(0, candidate.indexOf(':'));
        }
        return IPV4.matcher(candidate).matches() || IPV6.matcher(candidate).matches()
            ? candidate
            : null;
    }
}
