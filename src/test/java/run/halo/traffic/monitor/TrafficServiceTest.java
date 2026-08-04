package run.halo.traffic.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
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
        assertEquals(3, service.timeline(3, 1).stream()
            .mapToLong(TrafficModels.TimelinePoint::requests).sum());
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
        assertTrue(service.acknowledge(alert.id()));
        assertTrue(service.alerts(20).getFirst().acknowledged());
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
    }

    private void record(String ip, String path, int status, long requestBytes, long responseBytes) {
        service.record(new TrafficRecord(clock.millis(), ip, "GET", path, status,
            requestBytes, responseBytes, 5, "JUnit"), rules);
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
