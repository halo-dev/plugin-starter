package io.github.guqing.template;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * @author guqing
 * @since 2.0.0
 */
@RestController
@RequestMapping("/apis/apples.guqing.github.com/v1alpha1/apples")
public class ApplesController {

    @GetMapping
    public Mono<String> hello() {
        return Mono.just("Hello world");
    }
}
