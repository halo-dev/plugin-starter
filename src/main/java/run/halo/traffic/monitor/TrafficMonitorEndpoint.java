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
            .GET("/stream", this::stream)
            .POST("/alerts/{id}/acknowledge", this::acknowledge)
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
        if (!trafficService.acknowledge(request.pathVariable("id"))) {
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

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.traffic-monitor.halo.run", "v1alpha1");
    }
}
