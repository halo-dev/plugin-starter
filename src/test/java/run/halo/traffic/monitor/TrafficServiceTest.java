package run.halo.traffic.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import run.halo.traffic.monitor.TrafficModels.TrafficRecord;

class TrafficServiceTest {
    private MutableClock clock;
    private TrafficService service;
    private TrafficMonitorProperties rules;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(1_700_000_000_000L);
        service = new TrafficService(clock);
        rules = new TrafficMonitorProperties();
    }

    @Test
    void aggregatesSummaryTimelineAndIpRanking() {
        record("203.0.113.1", "/post/a", 200, 20, 100);
        record("203.0.113.1", "/post/b", 500, 30, 200);
        record("203.0.113.2", "/", 200, 10, 50);

        var summary = service.summary(60);
        assertEquals(3, summary.requests());
        assertEquals(2, summary.uniqueIps());
        assertEquals(60, summary.requestBytes());
        assertEquals(350, summary.responseBytes());
        assertEquals(1, summary.errorRequests());
        assertEquals("203.0.113.1", service.topIps(10).getFirst().ip());
        var timeline = service.timeline(3, 1);
        assertEquals(3, timeline.stream().mapToLong(TrafficModels.TimelinePoint::requests).sum());
        assertEquals(2, timeline.stream().flatMap(point -> point.ips().stream())
            .filter(item -> item.ip().equals("203.0.113.1"))
            .mapToLong(TrafficModels.IpCount::requests).sum());
    }

    @Test
    void raisesRateAlertOnceDuringCooldownAndCanAcknowledgeIt() {
        rules.setRequestsPerMinuteThreshold(3);
        rules.setAlertCooldownSeconds(60);
        record("203.0.113.9", "/a", 200, 0, 10);
        record("203.0.113.9", "/b", 200, 0, 10);
        record("203.0.113.9", "/c", 200, 0, 10);
        record("203.0.113.9", "/d", 200, 0, 10);

        var alerts = service.alerts(20);
        assertEquals(1, alerts.stream().filter(a -> a.type().equals("RATE")).count());
        var alert = alerts.getFirst();
        assertFalse(alert.acknowledged());
        assertTrue(service.acknowledgeAndBlock(alert.id(), 1_440));
        assertTrue(service.alerts(20).getFirst().acknowledged());
        assertEquals("alert-confirm", service.blockedIps().getFirst().source());
    }

    @Test
    void detectsSuspiciousPathAndResetClearsEverything() {
        record("198.51.100.2", "/.env", 404, 0, 20);
        assertTrue(service.alerts(10).stream().anyMatch(a -> a.type().equals("SUSPICIOUS_PATH")));

        service.reset();
        assertEquals(0, service.summary(60).requests());
        assertTrue(service.alerts(10).isEmpty());
    }

    @Test
    void removesTrafficAndAlertsOlderThanSevenDays() {
        record("198.51.100.7", "/wp-admin/", 404, 10, 20);
        assertEquals(1, service.summary(60).requests());
        assertFalse(service.alerts(10).isEmpty());

        clock.advance(TrafficService.RETENTION_MS + 61_000L);

        assertEquals(0, service.summary(10_080).requests());
        assertTrue(service.topIps(10).isEmpty());
        assertTrue(service.alerts(10).isEmpty());
        assertTrue(service.accessLogs("", "", null, 0, 0, 10).items().isEmpty());
    }

    @Test
    void autoBlocksIpAfterFailedLoginThreshold() {
        rules.setFailedLoginThreshold(3);
        rules.setFailedLoginWindowMinutes(10);
        rules.setBlockDurationMinutes(60);

        loginResult("203.0.113.66", true);
        loginResult("203.0.113.66", true);
        assertTrue(service.blockedIps().isEmpty());
        loginResult("203.0.113.66", true);

        assertEquals(1, service.blockedIps().size());
        assertEquals("203.0.113.66", service.blockedIps().getFirst().ip());
        assertEquals("auto-login", service.blockedIps().getFirst().source());
        assertTrue(service.alerts(20).stream().anyMatch(a -> a.type().equals("IP_AUTO_BLOCKED")));
    }

    @Test
    void whitelistBypassesAutomaticBlock() {
        rules.setIpWhitelist("203.0.113.0/24");
        rules.setFailedLoginThreshold(2);

        loginResult("203.0.113.77", true);
        loginResult("203.0.113.77", true);
        loginResult("203.0.113.77", true);

        assertTrue(service.blockedIps().isEmpty());
        assertTrue(service.isWhitelisted("203.0.113.77", rules));
    }

    @Test
    void manualBlockExpiresAndCanBeRemoved() {
        assertTrue(service.blockIp("198.51.100.9", "test", "manual", 1).isPresent());
        assertTrue(service.blockedAccess("198.51.100.9", clock.millis()).isPresent());
        assertEquals(1, service.blockedIps().getFirst().hitCount());
        assertTrue(service.unblockIp("198.51.100.9"));
        assertTrue(service.blockedIps().isEmpty());

        service.blockIp("198.51.100.9", "test", "manual", 1);
        clock.advance(60_001L);
        assertTrue(service.blockedIps().isEmpty());
    }

    @Test
    void trafficAnomalyImmediatelyBlocksIpForTwentyFourHours() {
        rules.setRequestsPerMinuteThreshold(1);

        record("198.51.100.88", "/normal", 200, 10, 20);

        var block = service.blockedIps().getFirst();
        assertEquals("198.51.100.88", block.ip());
        assertEquals("auto-traffic", block.source());
        assertEquals(24 * 60 * 60_000L, block.expiresAt() - block.blockedAt());
        assertTrue(service.alerts(20).stream().anyMatch(a -> a.type().equals("IP_AUTO_BLOCKED")));
    }

    @Test
    void permanentManualBlockDoesNotExpire() {
        var block = service.blockIp("198.51.100.90", "permanent-test", "manual", 0).orElseThrow();
        assertEquals(0, block.expiresAt());

        clock.advance(TrafficService.RETENTION_MS * 2);

        assertEquals(0, service.blockedIps().getFirst().expiresAt());
        assertTrue(service.blockedAccess("198.51.100.90", clock.millis()).isPresent());
    }

    @Test
    void filtersAccessLogsAndReturnsRedactedResponseDetail() {
        var record = new TrafficRecord(clock.millis(), "203.0.113.55", "GET", "/api/posts/one",
            200, 12, 32, 8, "JUnit");
        var response = new TrafficModels.ResponseSnapshot(
            "application/json",
            Map.of("Content-Type", List.of("application/json"),
                "Set-Cookie", List.of("SESSION=secret")),
            "{\"ok\":true}", true, false);
        service.record(record, rules, response);

        var page = service.accessLogs("203.0.113", "posts", 200, 0, 0, 10);
        assertEquals(1, page.total());
        assertEquals("/api/posts/one", page.items().getFirst().path());
        var detail = service.accessDetail(page.items().getFirst().id()).orElseThrow();
        assertEquals("{\"ok\":true}", detail.responseBody());
        assertEquals(List.of("[REDACTED]"), detail.responseHeaders().get("Set-Cookie"));
        assertEquals(0, service.accessLogs("198.51.100", "", null, 0, 0, 10).total());
    }

    private void record(String ip, String path, int status, long requestBytes, long responseBytes) {
        service.record(new TrafficRecord(clock.millis(), ip, "GET", path, status,
            requestBytes, responseBytes, 5, "JUnit"), rules);
    }

    private void loginResult(String ip, boolean failed) {
        var record = new TrafficRecord(clock.millis(), ip, "POST", "/login",
            failed ? 401 : 200, 128, 256, 5, "JUnit");
        service.record(record, rules);
        service.recordLoginResult(record, failed, rules);
    }

    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long millis) {
            this.millis = millis;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }

        private void advance(long amount) {
            millis += amount;
        }
    }
}
