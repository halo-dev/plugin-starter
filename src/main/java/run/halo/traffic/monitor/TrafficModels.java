package run.halo.traffic.monitor;

import java.util.List;
import java.util.Map;

public final class TrafficModels {
    private TrafficModels() {}

    public record TrafficRecord(
        long timestamp,
        String ip,
        String method,
        String path,
        int status,
        long requestBytes,
        long responseBytes,
        long durationMs,
        String userAgent
    ) {
        long totalBytes() {
            return requestBytes + responseBytes;
        }
    }

    public record Summary(
        long requests,
        long requestBytes,
        long responseBytes,
        long totalBytes,
        long uniqueIps,
        long errorRequests,
        long unacknowledgedAlerts,
        long since
    ) {}

    public record TimelinePoint(
        long timestamp,
        long requests,
        long requestBytes,
        long responseBytes,
        long errors,
        List<IpCount> ips
    ) {}

    public record IpCount(String ip, long requests) {}

    public record IpView(
        String ip,
        long requests,
        long totalBytes,
        long errorRequests,
        long firstSeen,
        long lastSeen,
        String lastPath,
        int lastStatus
    ) {}

    public record AlertView(
        String id,
        long timestamp,
        String ip,
        String type,
        String severity,
        String message,
        String path,
        boolean acknowledged
    ) {}

    public record BlockView(
        String ip,
        long blockedAt,
        long expiresAt,
        String reason,
        String source,
        long hitCount,
        long lastHit
    ) {}

    public record ResponseSnapshot(
        String contentType,
        Map<String, List<String>> headers,
        String body,
        boolean bodyCaptured,
        boolean bodyTruncated
    ) {
        public static ResponseSnapshot empty() {
            return new ResponseSnapshot("", Map.of(), "", false, false);
        }
    }

    public record AccessLogView(
        String id,
        long timestamp,
        String ip,
        String method,
        String path,
        int status,
        long requestBytes,
        long responseBytes,
        long durationMs,
        String contentType,
        String responsePreview,
        boolean bodyCaptured,
        boolean bodyTruncated
    ) {}

    public record AccessDetail(
        String id,
        long timestamp,
        String ip,
        String method,
        String path,
        int status,
        long requestBytes,
        long responseBytes,
        long durationMs,
        String userAgent,
        String contentType,
        Map<String, List<String>> responseHeaders,
        String responseBody,
        boolean bodyCaptured,
        boolean bodyTruncated
    ) {
        AccessLogView toView() {
            String normalized = responseBody == null ? "" : responseBody.replaceAll("\\s+", " ").trim();
            String preview = normalized.length() <= 180 ? normalized : normalized.substring(0, 180) + "…";
            return new AccessLogView(id, timestamp, ip, method, path, status, requestBytes,
                responseBytes, durationMs, contentType, preview, bodyCaptured, bodyTruncated);
        }
    }

    public record AccessLogPage(
        List<AccessLogView> items,
        long total,
        int offset,
        int limit
    ) {}

    public record Dashboard(
        Summary summary,
        List<TimelinePoint> timeline,
        List<IpView> topIps,
        List<AlertView> alerts,
        List<BlockView> blockedIps,
        long generatedAt
    ) {}
}
