package run.halo.traffic.monitor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import run.halo.traffic.monitor.TrafficModels.BlockView;
import run.halo.traffic.monitor.TrafficModels.ResponseSnapshot;
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
        if (!rules.isEnabled()) {
            return chain.filter(exchange);
        }

        long timestamp = System.currentTimeMillis();
        String ip = ClientIpResolver.resolve(exchange.getRequest(), rules.isTrustProxyHeaders());
        String requestPath = exchange.getRequest().getPath().value();
        if (!requestPath.startsWith(API_PREFIX) && !trafficService.isWhitelisted(ip, rules)) {
            var blocked = trafficService.blockedAccess(ip, timestamp);
            if (blocked.isPresent()) {
                return rejectBlocked(exchange, blocked.get(), timestamp);
            }
        }
        boolean loginAttempt = isLoginAttempt(exchange);
        if (!loginAttempt && shouldSkip(exchange, rules)) {
            return chain.filter(exchange);
        }

        long startedAt = System.nanoTime();
        AtomicLong requestBytes = new AtomicLong();
        AtomicLong responseBytes = new AtomicLong();
        AtomicBoolean requestBodyObserved = new AtomicBoolean();
        ResponseBodyCapture responseCapture = new ResponseBodyCapture(
            rules.isCaptureResponseBody(), rules.getResponseBodyMaxBytes());

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
                    .doOnNext(buffer -> {
                        responseBytes.addAndGet(buffer.readableByteCount());
                        responseCapture.capture(buffer, getHeaders().getContentType());
                    }));
            }

            @Override
            @NonNull
            public Mono<Void> writeAndFlushWith(
                @NonNull Publisher<? extends Publisher<? extends DataBuffer>> body
            ) {
                Flux<Publisher<? extends DataBuffer>> counted = Flux.from(body)
                    .map(inner -> Flux.from(inner)
                        .doOnNext(buffer -> {
                            responseBytes.addAndGet(buffer.readableByteCount());
                            responseCapture.capture(buffer, getHeaders().getContentType());
                        }));
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
            TrafficRecord record = new TrafficRecord(
                timestamp,
                ip,
                exchange.getRequest().getMethod().name(),
                path,
                status,
                requestBytes.get(),
                responseBytes.get(),
                durationMs,
                userAgent
            );
            trafficService.record(record, rules,
                responseCapture.snapshot(response.getHeaders(), rules.isCaptureResponseHeaders()));
            if (loginAttempt) {
                trafficService.recordLoginResult(record, isFailedLogin(response), rules);
            }
        });
    }

    private static boolean isLoginAttempt(ServerWebExchange exchange) {
        return HttpMethod.POST.equals(exchange.getRequest().getMethod())
            && "/login".equals(exchange.getRequest().getPath().value());
    }

    private static boolean isFailedLogin(ServerHttpResponseDecorator response) {
        int status = response.getStatusCode() == null ? 200 : response.getStatusCode().value();
        if (status == 401 || status == 403 || status == 429) {
            return true;
        }
        var location = response.getHeaders().getLocation();
        return status >= 300 && status < 400 && location != null
            && "/login".equals(location.getPath())
            && location.getQuery() != null
            && location.getQuery().startsWith("error");
    }

    private static Mono<Void> rejectBlocked(
        ServerWebExchange exchange,
        BlockView block,
        long now
    ) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("X-Traffic-Monitor-Blocked", "true");
        if (block.expiresAt() > 0) {
            long retryAfter = Math.max(1, (block.expiresAt() - now + 999) / 1_000L);
            response.getHeaders().set("Retry-After", Long.toString(retryAfter));
        }
        byte[] body = ("{\"status\":403,\"title\":\"IP blocked\",\"expiresAt\":"
            + block.expiresAt() + ",\"permanent\":" + (block.expiresAt() == 0) + "}")
            .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    static boolean shouldSkip(ServerWebExchange exchange, TrafficMonitorProperties rules) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(API_PREFIX)
            || path.startsWith("/console")
            || path.startsWith("/actuator")) {
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

    private static final class ResponseBodyCapture {
        private final boolean enabled;
        private final int limit;
        private final ByteArrayOutputStream output;
        private boolean truncated;
        private boolean textual;

        private ResponseBodyCapture(boolean enabled, int limit) {
            this.enabled = enabled;
            this.limit = Math.max(1_024, limit);
            this.output = new ByteArrayOutputStream(Math.min(this.limit, 8_192));
        }

        private synchronized void capture(DataBuffer buffer, MediaType contentType) {
            if (!enabled || !isTextual(contentType)) {
                return;
            }
            textual = true;
            int readable = buffer.readableByteCount();
            int remaining = limit - output.size();
            if (remaining <= 0) {
                truncated |= readable > 0;
                return;
            }
            int length = Math.min(readable, remaining);
            var byteBuffer = buffer.toByteBuffer(buffer.readPosition(), length);
            byte[] bytes = new byte[length];
            byteBuffer.get(bytes);
            output.writeBytes(bytes);
            truncated |= readable > length;
        }

        private synchronized ResponseSnapshot snapshot(
            org.springframework.http.HttpHeaders headers,
            boolean captureHeaders
        ) {
            MediaType contentType = headers.getContentType();
            var charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset() : StandardCharsets.UTF_8;
            String body = enabled && textual ? output.toString(charset) : "";
            Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
            if (captureHeaders) {
                headers.forEach((name, values) -> copiedHeaders.put(name, List.copyOf(values)));
            }
            return new ResponseSnapshot(
                contentType == null ? "" : contentType.toString(),
                copiedHeaders,
                body,
                enabled && textual,
                truncated
            );
        }

        private static boolean isTextual(MediaType contentType) {
            if (contentType == null) {
                return true;
            }
            String subtype = contentType.getSubtype().toLowerCase(Locale.ROOT);
            return "text".equalsIgnoreCase(contentType.getType())
                || subtype.contains("json")
                || subtype.contains("xml")
                || subtype.contains("javascript")
                || subtype.contains("html")
                || subtype.contains("form-urlencoded")
                || subtype.contains("graphql");
        }
    }
}
