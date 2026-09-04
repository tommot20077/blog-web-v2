package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.event.ArticleArchivedEvent;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.event.ArticleUpdatedEvent;
import dowob.xyz.blog.module.article.event.ArticleViewedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文章事件發布元件
 *
 * <p>統一封裝所有文章模組的 RabbitMQ 訊息發送邏輯，
 * 採 best-effort 策略：失敗時僅 log warn，不影響主流程。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    private final UserFacade userFacade;

    private final ArticleMapper articleMapper;

    // ──────────────────────────────────────────────
    // ContentChanged（SAVED / PUBLISHED / RESTORED）
    // ──────────────────────────────────────────────

    /**
     * 發送文章內容變更事件（輕量 marker，供 version 模組訂閱觸發快照）。
     *
     * @param article 變更後的文章實體
     * @param action  觸發動作（SAVED / PUBLISHED / RESTORED）
     */
    public void publishContentChanged(Article article, ArticleContentChangedEvent.Action action) {
        try {
            ArticleContentChangedEvent event = new ArticleContentChangedEvent(
                    article.getId(),
                    article.getUuid(),
                    article.getAuthorId(),
                    action,
                    Instant.now());
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_CONTENT_CHANGED,
                    event);
        } catch (Exception e) {
            log.warn("ContentChanged 事件 MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Updated（已發布文章內容更新）
    // ──────────────────────────────────────────────

    /**
     * 發送文章更新事件至 RabbitMQ（供搜尋模組更新索引）。
     *
     * <p>僅在文章狀態為 PUBLISHED 時才應呼叫。</p>
     *
     * @param article 更新後的文章實體
     */
    public void publishUpdated(Article article) {
        try {
            List<TagInfo> tags = articleMapper.findTagsByArticleUuid(article.getUuid());
            ArticleUpdatedEvent event = new ArticleUpdatedEvent(
                    article.getUuid(),
                    article.getAuthorId(),
                    article.getTitle(),
                    article.getPublishedAt(),
                    article.getSlug(),
                    article.getSummary(),
                    stripMarkdown(article.getContent()),
                    userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                    userFacade.getUserNicknameById(article.getAuthorId()).orElse(null),
                    tags);
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_UPDATED,
                    event);
        } catch (Exception e) {
            log.warn("ArticleUpdatedEvent MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Published（文章發布）
    // ──────────────────────────────────────────────

    /**
     * 發送文章發布事件至 RabbitMQ（供推薦模組清快取、搜尋模組建立索引）。
     *
     * @param article 已發布的文章實體
     * @param tags    文章標籤列表
     */
    public void publishPublished(Article article, List<TagInfo> tags) {
        try {
            ArticlePublishedEvent event = new ArticlePublishedEvent(
                    article.getUuid(),
                    article.getAuthorId(),
                    article.getTitle(),
                    article.getPublishedAt(),
                    article.getSlug(),
                    article.getSummary(),
                    stripMarkdown(article.getContent()),
                    userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                    userFacade.getUserNicknameById(article.getAuthorId()).orElse(null),
                    tags);
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_PUBLISHED,
                    event);
        } catch (Exception e) {
            log.warn("ArticlePublishedEvent MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Deleted（文章刪除）
    // ──────────────────────────────────────────────

    /**
     * 發 ArticleDeletedEvent — rich payload，因文章已刪 consumer 撈不到 entity。
     *
     * @param article      文章 entity（提供 id / uuid / authorId）
     * @param seriesId     文章所屬 series id（nullable）
     * @param categoryIds  文章 categories（caller 在 delete 前讀取；空 list 不可 null）
     * @param tagIds       文章 tags（caller 在 delete 前讀取；空 list 不可 null）
     */
    public void publishDeleted(Article article, Long seriesId,
                               List<UUID> categoryIds, List<UUID> tagIds) {
        try {
            ArticleDeletedEvent event = new ArticleDeletedEvent(
                UUID.randomUUID(),
                article.getId(),
                article.getUuid(),
                article.getAuthorId(),
                seriesId,
                categoryIds != null ? categoryIds : List.of(),
                tagIds != null ? tagIds : List.of(),
                Instant.now()
            );
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_DELETED,
                    event);
        } catch (Exception e) {
            log.warn("ArticleDeletedEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Archived（文章下架，PUBLISHED → ARCHIVED）
    // ──────────────────────────────────────────────

    /**
     * 發送文章下架事件至 RabbitMQ（供搜尋模組移除 Elasticsearch 索引）。
     *
     * <p>僅在文章狀態已轉為 ARCHIVED 且 DB 已 commit 後才應呼叫；
     * 與其他事件一致採 best-effort，失敗僅 log warn 不影響下架結果。</p>
     *
     * @param article 已下架的文章實體
     */
    public void publishArchived(Article article) {
        try {
            ArticleArchivedEvent event = new ArticleArchivedEvent(
                    UUID.randomUUID(),
                    article.getUuid(),
                    Instant.now());
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_ARCHIVED,
                    event);
        } catch (Exception e) {
            log.warn("ArticleArchivedEvent MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Tagged（文章標籤變更）
    // ──────────────────────────────────────────────

    /**
     * 發送文章標籤事件至 RabbitMQ（供標籤模組更新使用計數）。
     *
     * @param article 文章實體
     * @param tagIds  被標記的標籤 UUID 列表
     */
    public void publishTagged(Article article, List<UUID> tagIds) {
        try {
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_TAGGED,
                    new ArticleTagEvent(UUID.randomUUID(), article.getUuid(), tagIds));
        } catch (Exception e) {
            log.warn("ArticleTagEvent MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Viewed（文章被瀏覽）
    // ──────────────────────────────────────────────

    /**
     * 發送文章瀏覽事件至 RabbitMQ（通過 Redis 防刷後才呼叫）。
     *
     * @param articleUuid 文章公開 UUID
     */
    public void publishViewed(UUID articleUuid) {
        try {
            rabbitTemplate.convertAndSend(
                    ArticleRabbitMqConfig.EXCHANGE,
                    ArticleRabbitMqConfig.ROUTING_KEY_VIEWED,
                    new ArticleViewedEvent(UUID.randomUUID(), articleUuid, Instant.now()));
        } catch (Exception e) {
            log.warn("ArticleViewedEvent MQ 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────

    /**
     * 去除 Markdown 格式，回傳純文字（供 Elasticsearch 索引）。
     *
     * @param markdown Markdown 原文
     * @return 純文字內容
     */
    private String stripMarkdown(String markdown) {
        if (markdown == null)
            return "";
        return markdown
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("`[^`]*`", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*{1,2}([^*]+)\\*{1,2}", "$1")
                .replaceAll("_{1,2}([^_]+)_{1,2}", "$1")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^[-*_]{3,}$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }
}
