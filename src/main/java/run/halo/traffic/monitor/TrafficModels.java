package run.halo.traffic.monitor;

import java.util.List;

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
        long errors
    ) {}

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

    public record Dashboard(
        Summary summary,
        List<TimelinePoint> timeline,
        List<IpView> topIps,
        List<AlertView> alerts,
        long generatedAt
    ) {}
}
