package run.halo.traffic.monitor;

import java.time.Duration;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

@Component
public class TrafficMonitorEndpoint implements CustomEndpoint {
    private final TrafficService trafficService;

    public TrafficMonitorEndpoint(TrafficService trafficService) {
        this.trafficService = trafficService;
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("/dashboard", this::dashboard)
            .GET("/summary", this::summary)
            .GET("/timeline", this::timeline)
            .GET("/ips", this::ips)
            .GET("/alerts", this::alerts)
            .GET("/blocks", this::blocks)
            .GET("/records", this::records)
            .GET("/records/{id}", this::recordDetail)
            .GET("/stream", this::stream)
            .POST("/alerts/{id}/acknowledge", this::acknowledge)
            .POST("/blocks/{ip}", this::block)
            .DELETE("/blocks/{ip}", this::unblock)
            .DELETE("/data", this::reset)
            .build();
    }

    private Mono<ServerResponse> dashboard(ServerRequest request) {
        return ServerResponse.ok().bodyValue(trafficService.dashboard(queryInt(request, "minutes", 60)));
    }

    private Mono<ServerResponse> summary(ServerRequest request) {
        return ServerResponse.ok().bodyValue(trafficService.summary(queryInt(request, "minutes", 60)));
    }

    private Mono<ServerResponse> timeline(ServerRequest request) {
        int minutes = queryInt(request, "minutes", 60);
        int bucketMinutes = queryInt(request, "bucketMinutes", 1);
        return ServerResponse.ok().bodyValue(trafficService.timeline(minutes, bucketMinutes));
    }

    private Mono<ServerResponse> ips(ServerRequest request) {
        return ServerResponse.ok().bodyValue(trafficService.topIps(queryInt(request, "limit", 100)));
    }

    private Mono<ServerResponse> alerts(ServerRequest request) {
        return ServerResponse.ok().bodyValue(trafficService.alerts(queryInt(request, "limit", 100)));
    }

    private Mono<ServerResponse> blocks(ServerRequest request) {
        return ServerResponse.ok().bodyValue(trafficService.blockedIps());
    }

    private Mono<ServerResponse> records(ServerRequest request) {
        int minutes = Math.max(1, Math.min(10_080, queryInt(request, "minutes", 1_440)));
        long since = System.currentTimeMillis() - minutes * 60_000L;
        return ServerResponse.ok().bodyValue(trafficService.accessLogs(
            request.queryParam("ip").orElse(""),
            request.queryParam("path").orElse(""),
            queryInteger(request, "status"),
            since,
            queryInt(request, "offset", 0),
            queryInt(request, "limit", 100)
        ));
    }

    private Mono<ServerResponse> recordDetail(ServerRequest request) {
        return trafficService.accessDetail(request.pathVariable("id"))
            .map(value -> ServerResponse.ok().bodyValue(value))
            .orElseGet(() -> ServerResponse.notFound().build());
    }

    private Mono<ServerResponse> stream(ServerRequest request) {
        int minutes = queryInt(request, "minutes", 60);
        Flux<ServerSentEvent<TrafficModels.Dashboard>> events = Flux
            .interval(Duration.ZERO, Duration.ofSeconds(2))
            .map(sequence -> ServerSentEvent.<TrafficModels.Dashboard>builder()
                .id(Long.toString(sequence))
                .event("dashboard")
                .data(trafficService.dashboard(minutes))
                .build());
        return ServerResponse.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(events, ServerSentEvent.class);
    }

    private Mono<ServerResponse> acknowledge(ServerRequest request) {
        if (!trafficService.acknowledgeAndBlock(request.pathVariable("id"), 1_440)) {
            return ServerResponse.notFound().build();
        }
        return ServerResponse.noContent().build();
    }

    private Mono<ServerResponse> block(ServerRequest request) {
        String mode = request.queryParam("mode").orElse("24h");
        int minutes = switch (mode) {
            case "permanent" -> 0;
            case "7d" -> 10_080;
            case "30d" -> 43_200;
            case "custom" -> Math.max(1, Math.min(525_600,
                queryInt(request, "minutes", 1_440)));
            default -> 1_440;
        };
        String reason = request.queryParam("reason").orElse("Console 手动封禁");
        return trafficService.blockIp(request.pathVariable("ip"), reason, "manual", minutes)
            .map(value -> ServerResponse.ok().bodyValue(value))
            .orElseGet(() -> ServerResponse.badRequest().build());
    }

    private Mono<ServerResponse> unblock(ServerRequest request) {
        if (!trafficService.unblockIp(request.pathVariable("ip"))) {
            return ServerResponse.notFound().build();
        }
        return ServerResponse.noContent().build();
    }

    private Mono<ServerResponse> reset(ServerRequest request) {
        trafficService.reset();
        return ServerResponse.noContent().build();
    }

    private static int queryInt(ServerRequest request, String name, int defaultValue) {
        return request.queryParam(name).map(value -> {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }).orElse(defaultValue);
    }

    private static Integer queryInteger(ServerRequest request, String name) {
        return request.queryParam(name).map(value -> {
            try {
                return Integer.valueOf(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }).orElse(null);
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.traffic-monitor.halo.run", "v1alpha1");
    }
}
