package io.github.guqing.template.post;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import run.halo.app.plugin.ApiVersion;

/**
 * @author guqing
 * @since 2.0.0
 */
@ApiVersion("v1alpha1")
@RestController
@RequestMapping("posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    public Flux<Post> list() {
        return postService.list();
    }
}
