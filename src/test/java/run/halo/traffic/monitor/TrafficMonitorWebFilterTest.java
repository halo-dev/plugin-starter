package run.halo.traffic.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;

class TrafficMonitorWebFilterTest {
    @Test
    void blocksActiveIpAndAllowsWhitelistedIp() {
        var service = new TrafficService(Clock.systemUTC());
        var rules = new TrafficMonitorProperties();
        var fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("rules"), eq(TrafficMonitorProperties.class)))
            .thenReturn(Mono.just(rules));
        var filter = new TrafficMonitorWebFilter(service, fetcher);
        WebFilterChain chain = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.OK);
            return exchange.getResponse().setComplete();
        };

        service.blockIp("203.0.113.9", "test", "manual", 60);
        var blocked = exchange("203.0.113.9");
        filter.filter(blocked, chain).block();
        assertEquals(HttpStatus.FORBIDDEN, blocked.getResponse().getStatusCode());
        assertEquals("true", blocked.getResponse().getHeaders().getFirst("X-Traffic-Monitor-Blocked"));

        rules.setIpWhitelist("203.0.113.9");
        var allowed = exchange("203.0.113.9");
        filter.filter(allowed, chain).block();
        assertEquals(HttpStatus.OK, allowed.getResponse().getStatusCode());
    }

    @Test
    void failedHaloLoginTriggersAutomaticBlock() {
        var service = new TrafficService(Clock.systemUTC());
        var rules = new TrafficMonitorProperties();
        rules.setFailedLoginThreshold(1);
        var fetcher = mock(ReactiveSettingFetcher.class);
        when(fetcher.fetch(eq("rules"), eq(TrafficMonitorProperties.class)))
            .thenReturn(Mono.just(rules));
        var filter = new TrafficMonitorWebFilter(service, fetcher);
        WebFilterChain failedLogin = exchange -> {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return exchange.getResponse().setComplete();
        };
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/login")
            .remoteAddress(new InetSocketAddress("198.51.100.44", 8080))
            .build());

        filter.filter(exchange, failedLogin).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals("198.51.100.44", service.blockedIps().getFirst().ip());
        assertEquals("auto-login", service.blockedIps().getFirst().source());
    }

    private static MockServerWebExchange exchange(String ip) {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/")
            .remoteAddress(new InetSocketAddress(ip, 8080))
            .build());
    }
}
