package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
class ArticleViewSubService {

    private static final String VIEW_KEY_PREFIX = "view:";
    private static final long VIEW_DEDUP_TTL_MINUTES = 5;

    private final StringRedisTemplate stringRedisTemplate;
    private final ArticleEventPublisher articleEventPublisher;

    void recordView(UUID articleUuid, ArticleStatus status, String clientIp) {
        if (!status.isPubliclyVisible()) {
            return;
        }

        String viewKey = VIEW_KEY_PREFIX + articleUuid + ":" + clientIp;
        Boolean firstVisit = stringRedisTemplate.opsForValue()
                .setIfAbsent(viewKey, "1", VIEW_DEDUP_TTL_MINUTES, TimeUnit.MINUTES);
        if (Boolean.TRUE.equals(firstVisit)) {
            articleEventPublisher.publishViewed(articleUuid);
        }
    }
}
