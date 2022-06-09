package io.github.guqing.template.post;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * @author guqing
 * @since 2.0.0
 */
@Service
public class PostService {

    @Autowired
    private PostRepository postRepository;

    public Flux<Post> list() {
        return postRepository.findAll();
    }
}
