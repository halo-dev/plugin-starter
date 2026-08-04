package run.halo.traffic.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

class ClientIpResolverTest {

    @Test
    void usesRemoteAddressWhenProxyHeadersAreDisabled() {
        var request = MockServerHttpRequest.get("/")
            .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
            .header("X-Forwarded-For", "203.0.113.7")
            .build();

        assertEquals("127.0.0.1", ClientIpResolver.resolve(request, false));
    }

    @Test
    void resolvesFirstTrustedForwardedAddress() {
        var request = MockServerHttpRequest.get("/")
            .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
            .header("X-Forwarded-For", "203.0.113.7, 10.0.0.2")
            .build();

        assertEquals("203.0.113.7", ClientIpResolver.resolve(request, true));
    }

    @Test
    void normalizesIpv4PortAndBracketedIpv6() {
        assertEquals("192.0.2.4", ClientIpResolver.normalize("192.0.2.4:1234"));
        assertEquals("2001:db8::1", ClientIpResolver.normalize("[2001:db8::1]:443"));
    }
}
