package run.halo.traffic.monitor;

import static org.springframework.test.web.reactive.server.WebTestClient.bindToRouterFunction;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import run.halo.traffic.monitor.TrafficModels.TrafficRecord;

class TrafficMonitorEndpointTest {

    @Test
    void servesDashboardAndResetsData() {
        var service = new TrafficService(Clock.systemUTC());
        var rules = new TrafficMonitorProperties();
        service.record(new TrafficRecord(System.currentTimeMillis(), "203.0.113.8", "GET", "/",
            200, 0, 128, 3, "JUnit"), rules);
        var client = bindToRouterFunction(new TrafficMonitorEndpoint(service).endpoint()).build();

        client.get().uri("/dashboard?minutes=60").exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.summary.requests").isEqualTo(1)
            .jsonPath("$.topIps[0].ip").isEqualTo("203.0.113.8");

        client.delete().uri("/data").exchange().expectStatus().isNoContent();
        client.get().uri("/summary?minutes=60").exchange()
            .expectBody().jsonPath("$.requests").isEqualTo(0);
    }
}
