package io.github.guqing.template.post;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * @author guqing
 * @since 2.0.0
 */
@Data
public class Post {
    private String id;
    private String userId;
    private String topicId;
    private String raw;
    private String cooked;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;
    private String replyToPostNumber;
    private Integer replyCount;
    private Integer quoteCount;
    private Integer likeCount;
    private Integer offTopicCount;
    private Integer postType;
    private Integer sortOrder;
}
