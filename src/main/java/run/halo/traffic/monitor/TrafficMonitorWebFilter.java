package run.halo.traffic.monitor;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.security.AdditionalWebFilter;
import run.halo.traffic.monitor.TrafficModels.TrafficRecord;

@Component
public class TrafficMonitorWebFilter implements AdditionalWebFilter {
    static final String API_PREFIX = "/apis/console.api.traffic-monitor.halo.run/";

    private final TrafficService trafficService;
    private final ReactiveSettingFetcher settingFetcher;

    public TrafficMonitorWebFilter(TrafficService trafficService, ReactiveSettingFetcher settingFetcher) {
        this.trafficService = trafficService;
        this.settingFetcher = settingFetcher;
    }

    @Override
    @NonNull
    public Mono<Void> filter(@NonNull ServerWebExchange exchange, @NonNull WebFilterChain chain) {
        return settingFetcher.fetch("rules", TrafficMonitorProperties.class)
            .timeout(Duration.ofSeconds(1))
            .onErrorReturn(new TrafficMonitorProperties())
            .defaultIfEmpty(new TrafficMonitorProperties())
            .flatMap(rules -> monitor(exchange, chain, rules));
    }

    private Mono<Void> monitor(
        ServerWebExchange exchange,
        WebFilterChain chain,
        TrafficMonitorProperties rules
    ) {
        if (!rules.isEnabled() || shouldSkip(exchange, rules)) {
            return chain.filter(exchange);
        }

        long startedAt = System.nanoTime();
        long timestamp = System.currentTimeMillis();
        AtomicLong requestBytes = new AtomicLong();
        AtomicLong responseBytes = new AtomicLong();
        AtomicBoolean requestBodyObserved = new AtomicBoolean();

        ServerHttpRequestDecorator request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            @NonNull
            public Flux<DataBuffer> getBody() {
                return super.getBody().doOnNext(buffer -> {
                    requestBodyObserved.set(true);
                    requestBytes.addAndGet(buffer.readableByteCount());
                });
            }
        };

        ServerHttpResponseDecorator response = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            @NonNull
            public Mono<Void> writeWith(@NonNull Publisher<? extends DataBuffer> body) {
                return super.writeWith(Flux.from(body)
                    .doOnNext(buffer -> responseBytes.addAndGet(buffer.readableByteCount())));
            }

            @Override
            @NonNull
            public Mono<Void> writeAndFlushWith(
                @NonNull Publisher<? extends Publisher<? extends DataBuffer>> body
            ) {
                Flux<Publisher<? extends DataBuffer>> counted = Flux.from(body)
                    .map(inner -> Flux.from(inner)
                        .doOnNext(buffer -> responseBytes.addAndGet(buffer.readableByteCount())));
                return super.writeAndFlushWith(counted);
            }
        };

        ServerWebExchange decorated = exchange.mutate().request(request).response(response).build();
        return chain.filter(decorated).doFinally(signal -> {
            if (!requestBodyObserved.get()) {
                requestBytes.set(Math.max(0, exchange.getRequest().getHeaders().getContentLength()));
            }
            int status = response.getStatusCode() == null ? 200 : response.getStatusCode().value();
            long durationMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
            String userAgent = safe(exchange.getRequest().getHeaders().getFirst("User-Agent"), 256);
            String path = safe(exchange.getRequest().getURI().getRawPath(), 512);
            trafficService.record(new TrafficRecord(
                timestamp,
                ClientIpResolver.resolve(exchange.getRequest(), rules.isTrustProxyHeaders()),
                exchange.getRequest().getMethod().name(),
                path,
                status,
                requestBytes.get(),
                responseBytes.get(),
                durationMs,
                userAgent
            ), rules);
        });
    }

    static boolean shouldSkip(ServerWebExchange exchange, TrafficMonitorProperties rules) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(API_PREFIX)
            || path.startsWith("/console")
            || path.startsWith("/actuator")
            || path.startsWith("/login")) {
            return true;
        }
        if (!rules.isIncludeApiTraffic() && path.startsWith("/apis/")) {
            return true;
        }
        return rules.isIgnoreStaticResources() && isStaticResource(path);
    }

    private static boolean isStaticResource(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        return value.startsWith("/upload/")
            || value.startsWith("/themes/")
            || value.startsWith("/plugins/")
            || value.matches(".*\\.(?:css|js|mjs|map|png|jpe?g|gif|webp|svg|ico|woff2?|ttf|eot)$");
    }

    private static String safe(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
