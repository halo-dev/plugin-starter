package run.halo.traffic.monitor;

import static run.halo.traffic.monitor.TrafficModels.AlertView;
import static run.halo.traffic.monitor.TrafficModels.AccessDetail;
import static run.halo.traffic.monitor.TrafficModels.AccessLogPage;
import static run.halo.traffic.monitor.TrafficModels.BlockView;
import static run.halo.traffic.monitor.TrafficModels.Dashboard;
import static run.halo.traffic.monitor.TrafficModels.IpView;
import static run.halo.traffic.monitor.TrafficModels.Summary;
import static run.halo.traffic.monitor.TrafficModels.TimelinePoint;
import static run.halo.traffic.monitor.TrafficModels.TrafficRecord;
import static run.halo.traffic.monitor.TrafficModels.ResponseSnapshot;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TrafficService {
    private static final long MINUTE_MS = 60_000L;
    static final long RETENTION_MS = 7L * 24 * 60 * MINUTE_MS;
    private static final int MAX_ALERTS = 500;
    private static final Pattern SUSPICIOUS_PATH = Pattern.compile(
        "(?i)(?:^|/)(?:\\.env|\\.git|wp-admin|wp-login\\.php|phpmyadmin|actuator|server-status|"
            + "vendor/phpunit|boaform|cgi-bin)(?:/|$)");
    private static final Set<String> SENSITIVE_RESPONSE_HEADERS = Set.of(
        "set-cookie", "proxy-authenticate", "www-authenticate", "authorization");

    private final Clock clock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();
    private final Map<String, IpStats> ipStats = new ConcurrentHashMap<>();
    private final Map<String, AlertView> alerts = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> alertOrder = new ConcurrentLinkedDeque<>();
    private final Map<String, Long> alertCooldowns = new ConcurrentHashMap<>();
    private final Map<String, BlockEntry> blockedIps = new ConcurrentHashMap<>();
    private final Map<String, LoginFailureWindow> loginFailures = new ConcurrentHashMap<>();
    private final Map<String, AccessDetail> accessDetails = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> accessOrder = new ConcurrentLinkedDeque<>();
    private final AtomicLong lastCleanup = new AtomicLong();

    public TrafficService() {
        this(Clock.systemUTC());
    }

    TrafficService(Clock clock) {
        this.clock = clock;
    }

    public void record(TrafficRecord record, TrafficMonitorProperties rules) {
        record(record, rules, ResponseSnapshot.empty());
    }

    public void record(
        TrafficRecord record,
        TrafficMonitorProperties rules,
        ResponseSnapshot response
    ) {
        long minute = floorMinute(record.timestamp());
        Bucket bucket = buckets.computeIfAbsent(minute, ignored -> new Bucket());
        bucket.add(record);

        IpStats stats = ipStats.computeIfAbsent(record.ip(), ignored -> new IpStats(record.timestamp()));
        WindowState window = stats.add(record);

        if (window.requests() >= rules.getRequestsPerMinuteThreshold()) {
            maybeAlert(record, "RATE", "high", "1 分钟请求数 " + window.requests()
                + " 已超过阈值 " + rules.getRequestsPerMinuteThreshold(), rules);
        }
        if (window.bytes() >= rules.getBytesPerMinuteThreshold()) {
            maybeAlert(record, "BANDWIDTH", "high", "1 分钟流量 " + window.bytes()
                + " bytes 已超过阈值 " + rules.getBytesPerMinuteThreshold(), rules);
        }
        if (record.totalBytes() >= rules.getSingleRequestBytesThreshold()) {
            maybeAlert(record, "LARGE_TRANSFER", "medium", "单次请求流量 "
                + record.totalBytes() + " bytes 超过阈值", rules);
        }
        if (window.serverErrors() >= rules.getServerErrorsPerMinuteThreshold()) {
            maybeAlert(record, "SERVER_ERROR_BURST", "high", "1 分钟 5xx 错误数 "
                + window.serverErrors() + " 已超过阈值", rules);
        }
        if (SUSPICIOUS_PATH.matcher(record.path()).find()) {
            maybeAlert(record, "SUSPICIOUS_PATH", "medium", "访问了常见扫描路径", rules);
        }

        storeAccessDetail(record, response, rules.getMaxStoredRecords());

        cleanupIfNeeded(record.timestamp());
    }

    public Dashboard dashboard(int minutes) {
        cleanupIfNeeded(clock.millis());
        int range = clamp(minutes, 5, 1_440);
        return new Dashboard(
            summary(range), timeline(range, chooseBucketMinutes(range)), topIps(50), alerts(50),
            blockedIps(), clock.millis());
    }

    public Summary summary(int minutes) {
        cleanupIfNeeded(clock.millis());
        long now = clock.millis();
        long since = now - clamp(minutes, 1, 10_080) * MINUTE_MS;
        long requests = 0;
        long requestBytes = 0;
        long responseBytes = 0;
        long errors = 0;
        Set<String> ips = new HashSet<>();
        for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) {
            if (entry.getKey() < floorMinute(since)) {
                continue;
            }
            Bucket bucket = entry.getValue();
            requests += bucket.requests.sum();
            requestBytes += bucket.requestBytes.sum();
            responseBytes += bucket.responseBytes.sum();
            errors += bucket.errors.sum();
            ips.addAll(bucket.ips);
        }
        long unacknowledged = alerts.values().stream().filter(alert -> !alert.acknowledged()).count();
        return new Summary(requests, requestBytes, responseBytes, requestBytes + responseBytes,
            ips.size(), errors, unacknowledged, since);
    }

    public List<TimelinePoint> timeline(int minutes, int bucketMinutes) {
        cleanupIfNeeded(clock.millis());
        int range = clamp(minutes, 1, 10_080);
        int size = clamp(bucketMinutes, 1, 1_440);
        long step = size * MINUTE_MS;
        long end = (clock.millis() / step) * step;
        long start = end - (long) (Math.ceil((double) range / size) - 1) * step;
        Map<Long, TimelineAccumulator> result = new ConcurrentHashMap<>();
        for (long timestamp = start; timestamp <= end; timestamp += step) {
            result.put(timestamp, new TimelineAccumulator());
        }
        for (Map.Entry<Long, Bucket> entry : buckets.entrySet()) {
            long timestamp = (entry.getKey() / step) * step;
            TimelineAccumulator accumulator = result.get(timestamp);
            if (accumulator != null) {
                accumulator.add(entry.getValue());
            }
        }
        return result.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getValue().toView(entry.getKey()))
            .toList();
    }

    public List<IpView> topIps(int limit) {
        cleanupIfNeeded(clock.millis());
        return ipStats.entrySet().stream()
            .map(entry -> entry.getValue().toView(entry.getKey()))
            .sorted(Comparator.comparingLong(IpView::requests).reversed()
                .thenComparing(Comparator.comparingLong(IpView::lastSeen).reversed()))
            .limit(clamp(limit, 1, 500))
            .toList();
    }

    public List<AlertView> alerts(int limit) {
        cleanupIfNeeded(clock.millis());
        List<AlertView> result = new ArrayList<>();
        for (String id : alertOrder) {
            AlertView alert = alerts.get(id);
            if (alert != null) {
                result.add(alert);
            }
            if (result.size() >= clamp(limit, 1, MAX_ALERTS)) {
                break;
            }
        }
        return result;
    }

    public AccessLogPage accessLogs(
        String ip,
        String path,
        Integer status,
        long since,
        int offset,
        int limit
    ) {
        cleanupIfNeeded(clock.millis());
        String ipFilter = normalizeFilter(ip);
        String pathFilter = normalizeFilter(path);
        int pageOffset = Math.max(0, offset);
        int pageLimit = clamp(limit, 1, 200);
        long matched = 0;
        List<TrafficModels.AccessLogView> items = new ArrayList<>();
        for (String id : accessOrder) {
            AccessDetail detail = accessDetails.get(id);
            if (detail == null
                || detail.timestamp() < since
                || !containsIgnoreCase(detail.ip(), ipFilter)
                || !containsIgnoreCase(detail.path(), pathFilter)
                || (status != null && detail.status() != status)) {
                continue;
            }
            if (matched >= pageOffset && items.size() < pageLimit) {
                items.add(detail.toView());
            }
            matched++;
        }
        return new AccessLogPage(items, matched, pageOffset, pageLimit);
    }

    public Optional<AccessDetail> accessDetail(String id) {
        cleanupIfNeeded(clock.millis());
        return Optional.ofNullable(accessDetails.get(id));
    }

    public boolean acknowledge(String id) {
        return alerts.computeIfPresent(id, (key, old) -> new AlertView(
            old.id(), old.timestamp(), old.ip(), old.type(), old.severity(), old.message(), old.path(), true
        )) != null;
    }

    public boolean isWhitelisted(String ip, TrafficMonitorProperties rules) {
        return IpRuleMatcher.matches(ip, rules.getIpWhitelist());
    }

    public Optional<BlockView> blockedAccess(String ip, long now) {
        BlockEntry entry = blockedIps.get(ip);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.isExpired(now)) {
            blockedIps.remove(ip, entry);
            return Optional.empty();
        }
        entry.hit(now);
        return Optional.of(entry.toView(ip));
    }

    public List<BlockView> blockedIps() {
        long now = clock.millis();
        blockedIps.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        return blockedIps.entrySet().stream()
            .map(entry -> entry.getValue().toView(entry.getKey()))
            .sorted(Comparator.comparingLong(BlockView::blockedAt).reversed())
            .toList();
    }

    public Optional<BlockView> blockIp(String ip, String reason, String source, int durationMinutes) {
        String normalized = ClientIpResolver.normalize(ip);
        if (normalized == null || "unknown".equals(normalized)) {
            return Optional.empty();
        }
        long now = clock.millis();
        long expiresAt = now + Math.max(1, durationMinutes) * MINUTE_MS;
        BlockEntry entry = blockedIps.compute(normalized, (ignored, current) -> {
            if (current == null || current.isExpired(now)) {
                return new BlockEntry(now, expiresAt, safeText(reason, "手动封禁"),
                    safeText(source, "manual"));
            }
            current.extend(expiresAt, safeText(reason, current.reason),
                safeText(source, current.source));
            return current;
        });
        log.warn("IP blocked ip={} source={} expiresAt={} reason={}",
            normalized, entry.source, entry.expiresAt.get(), entry.reason);
        return Optional.of(entry.toView(normalized));
    }

    public boolean unblockIp(String ip) {
        String normalized = ClientIpResolver.normalize(ip);
        if (normalized == null) {
            return false;
        }
        loginFailures.remove(normalized);
        return blockedIps.remove(normalized) != null;
    }

    public void recordLoginResult(
        TrafficRecord record,
        boolean failed,
        TrafficMonitorProperties rules
    ) {
        if (!failed) {
            loginFailures.remove(record.ip());
            return;
        }
        long windowMs = rules.getFailedLoginWindowMinutes() * MINUTE_MS;
        LoginFailureWindow window = loginFailures.computeIfAbsent(
            record.ip(), ignored -> new LoginFailureWindow());
        int attempts = window.add(record.timestamp(), windowMs);
        maybeAlert(record, "LOGIN_FAILURE", "medium",
            rules.getFailedLoginWindowMinutes() + " 分钟内登录失败 " + attempts + " 次", rules);

        if (rules.isAutoBlockFailedLogins()
            && attempts >= rules.getFailedLoginThreshold()
            && !isWhitelisted(record.ip(), rules)) {
            blockIp(record.ip(), "登录失败达到阈值 " + attempts + " 次", "auto-login",
                rules.getBlockDurationMinutes());
            maybeAlert(record, "IP_AUTO_BLOCKED", "high",
                "登录失败达到阈值，IP 已自动封禁 " + rules.getBlockDurationMinutes() + " 分钟", rules);
            loginFailures.remove(record.ip());
        }
    }

    public void reset() {
        buckets.clear();
        ipStats.clear();
        alerts.clear();
        alertOrder.clear();
        alertCooldowns.clear();
        loginFailures.clear();
        accessDetails.clear();
        accessOrder.clear();
    }

    private void storeAccessDetail(
        TrafficRecord record,
        ResponseSnapshot response,
        int maxStoredRecords
    ) {
        ResponseSnapshot normalized = response == null ? ResponseSnapshot.empty() : response;
        String id = UUID.randomUUID().toString();
        AccessDetail detail = new AccessDetail(
            id,
            record.timestamp(),
            record.ip(),
            record.method(),
            record.path(),
            record.status(),
            record.requestBytes(),
            record.responseBytes(),
            record.durationMs(),
            record.userAgent(),
            safeText(normalized.contentType(), ""),
            sanitizeHeaders(normalized.headers()),
            normalized.body() == null ? "" : normalized.body(),
            normalized.bodyCaptured(),
            normalized.bodyTruncated()
        );
        accessDetails.put(id, detail);
        accessOrder.addFirst(id);
        while (accessOrder.size() > maxStoredRecords) {
            String removed = accessOrder.pollLast();
            if (removed != null) {
                accessDetails.remove(removed);
            }
        }
    }

    private void maybeAlert(
        TrafficRecord record,
        String type,
        String severity,
        String message,
        TrafficMonitorProperties rules
    ) {
        String cooldownKey = record.ip() + ':' + type;
        long cooldown = rules.getAlertCooldownSeconds() * 1_000L;
        Long previous = alertCooldowns.putIfAbsent(cooldownKey, record.timestamp());
        if (previous != null) {
            if (record.timestamp() - previous < cooldown
                || !alertCooldowns.replace(cooldownKey, previous, record.timestamp())) {
                return;
            }
        }
        String id = UUID.randomUUID().toString();
        alerts.put(id, new AlertView(id, record.timestamp(), record.ip(), type, severity,
            message, record.path(), false));
        alertOrder.addFirst(id);
        log.warn("Traffic anomaly [{}] ip={} path={} message={}",
            type, record.ip(), record.path(), message);
        while (alertOrder.size() > MAX_ALERTS) {
            String removed = alertOrder.pollLast();
            if (removed != null) {
                alerts.remove(removed);
            }
        }
    }

    private void cleanupIfNeeded(long now) {
        long previous = lastCleanup.get();
        if (now - previous < MINUTE_MS || !lastCleanup.compareAndSet(previous, now)) {
            return;
        }
        long cutoff = now - RETENTION_MS;
        buckets.keySet().removeIf(timestamp -> timestamp < floorMinute(cutoff));
        ipStats.entrySet().removeIf(entry -> entry.getValue().lastSeen.get() < cutoff);
        alertCooldowns.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        blockedIps.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        loginFailures.entrySet().removeIf(entry -> entry.getValue().lastSeen() < cutoff);
        accessOrder.removeIf(id -> {
            AccessDetail detail = accessDetails.get(id);
            if (detail == null || detail.timestamp() < cutoff) {
                accessDetails.remove(id);
                return true;
            }
            return false;
        });
        alertOrder.removeIf(id -> {
            AlertView alert = alerts.get(id);
            if (alert == null || alert.timestamp() < cutoff) {
                alerts.remove(id);
                return true;
            }
            return false;
        });
    }

    private static int chooseBucketMinutes(int rangeMinutes) {
        if (rangeMinutes <= 120) {
            return 1;
        }
        if (rangeMinutes <= 720) {
            return 5;
        }
        return 15;
    }

    private static long floorMinute(long timestamp) {
        return (timestamp / MINUTE_MS) * MINUTE_MS;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 256 ? trimmed : trimmed.substring(0, 256);
    }

    private static Map<String, List<String>> sanitizeHeaders(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (result.size() >= 100) {
                break;
            }
            String name = safeText(entry.getKey(), "");
            if (name.isEmpty()) {
                continue;
            }
            if (SENSITIVE_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                result.put(name, List.of("[REDACTED]"));
                continue;
            }
            List<String> values = entry.getValue() == null ? List.of() : entry.getValue().stream()
                .limit(20)
                .map(value -> {
                    if (value == null) {
                        return "";
                    }
                    return value.length() <= 4_096 ? value : value.substring(0, 4_096) + "…";
                })
                .toList();
            result.put(name, values);
        }
        return Map.copyOf(result);
    }

    private static String normalizeFilter(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsIgnoreCase(String value, String filter) {
        return filter.isEmpty()
            || (value != null && value.toLowerCase(Locale.ROOT).contains(filter));
    }

    private static final class Bucket {
        private final LongAdder requests = new LongAdder();
        private final LongAdder requestBytes = new LongAdder();
        private final LongAdder responseBytes = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final Set<String> ips = ConcurrentHashMap.newKeySet();

        void add(TrafficRecord record) {
            requests.increment();
            requestBytes.add(record.requestBytes());
            responseBytes.add(record.responseBytes());
            if (record.status() >= 500) {
                errors.increment();
            }
            ips.add(record.ip());
        }
    }

    private static final class TimelineAccumulator {
        private long requests;
        private long requestBytes;
        private long responseBytes;
        private long errors;

        synchronized void add(Bucket bucket) {
            requests += bucket.requests.sum();
            requestBytes += bucket.requestBytes.sum();
            responseBytes += bucket.responseBytes.sum();
            errors += bucket.errors.sum();
        }

        synchronized TimelinePoint toView(long timestamp) {
            return new TimelinePoint(timestamp, requests, requestBytes, responseBytes, errors);
        }
    }

    private record WindowSample(long timestamp, long bytes, boolean serverError) {}

    private record WindowState(long requests, long bytes, long serverErrors) {}

    private static final class IpStats {
        private final LongAdder requests = new LongAdder();
        private final LongAdder totalBytes = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final AtomicLong firstSeen;
        private final AtomicLong lastSeen;
        private final ArrayDeque<WindowSample> window = new ArrayDeque<>();
        private long windowBytes;
        private long windowErrors;
        private volatile String lastPath = "/";
        private volatile int lastStatus = 200;

        private IpStats(long timestamp) {
            firstSeen = new AtomicLong(timestamp);
            lastSeen = new AtomicLong(timestamp);
        }

        synchronized WindowState add(TrafficRecord record) {
            requests.increment();
            totalBytes.add(record.totalBytes());
            if (record.status() >= 500) {
                errors.increment();
            }
            lastSeen.set(record.timestamp());
            lastPath = record.path();
            lastStatus = record.status();

            long cutoff = record.timestamp() - MINUTE_MS;
            while (!window.isEmpty() && window.peekFirst().timestamp() < cutoff) {
                WindowSample removed = window.removeFirst();
                windowBytes -= removed.bytes();
                if (removed.serverError()) {
                    windowErrors--;
                }
            }
            WindowSample sample = new WindowSample(record.timestamp(), record.totalBytes(), record.status() >= 500);
            window.addLast(sample);
            windowBytes += sample.bytes();
            if (sample.serverError()) {
                windowErrors++;
            }
            return new WindowState(window.size(), windowBytes, windowErrors);
        }

        IpView toView(String ip) {
            return new IpView(ip, requests.sum(), totalBytes.sum(), errors.sum(), firstSeen.get(),
                lastSeen.get(), lastPath, lastStatus);
        }
    }

    private static final class BlockEntry {
        private final long blockedAt;
        private final AtomicLong expiresAt;
        private final LongAdder hitCount = new LongAdder();
        private final AtomicLong lastHit = new AtomicLong();
        private volatile String reason;
        private volatile String source;

        private BlockEntry(long blockedAt, long expiresAt, String reason, String source) {
            this.blockedAt = blockedAt;
            this.expiresAt = new AtomicLong(expiresAt);
            this.reason = reason;
            this.source = source;
        }

        private boolean isExpired(long now) {
            return now >= expiresAt.get();
        }

        private void extend(long newExpiresAt, String newReason, String newSource) {
            expiresAt.accumulateAndGet(newExpiresAt, Math::max);
            reason = newReason;
            source = newSource;
        }

        private void hit(long now) {
            hitCount.increment();
            lastHit.set(now);
        }

        private BlockView toView(String ip) {
            return new BlockView(ip, blockedAt, expiresAt.get(), reason, source,
                hitCount.sum(), lastHit.get());
        }
    }

    private static final class LoginFailureWindow {
        private final ArrayDeque<Long> timestamps = new ArrayDeque<>();

        private synchronized int add(long timestamp, long windowMs) {
            long cutoff = timestamp - windowMs;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.removeFirst();
            }
            timestamps.addLast(timestamp);
            return timestamps.size();
        }

        private synchronized long lastSeen() {
            return timestamps.isEmpty() ? 0 : timestamps.peekLast();
        }
    }
}
