package io.github.guqing.template.post;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * @author guqing
 * @since 2.0.0
 */
@Component
public class PostRepository {

    private final Map<String, Post> postMap = new ConcurrentHashMap<>();

    public PostRepository() {
        Post post = new Post();
        post.setId(UUID.randomUUID().toString());
        post.setRaw("静态方法适合生成简单的序列，当需要复杂的逻辑时，则应该使用 generate() 或 create() 方法。\n");
        post.setCooked(post.getRaw());
        post.setPostType(1);
        post.setCreatedAt(LocalDateTime.now());
        post.setLikeCount(1);
        post.setReplyCount(0);
        post.setOffTopicCount(0);
        post.setUserId(UUID.randomUUID().toString());
        post.setTopicId(UUID.randomUUID().toString());
        post.setSortOrder(1);
        postMap.put(post.getId(), post);
    }

    public Flux<Post> findAll() {
        return Flux.create(sink -> {
            for (Post value : postMap.values()) {
                sink.next(value);
            }
            sink.complete();
        });
    }
}
