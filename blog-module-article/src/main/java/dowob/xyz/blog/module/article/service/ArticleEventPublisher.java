package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.event.ArticleEventContract;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.infrastructure.event.ArticleArchivedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
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

    /**
     * series uuid 的轉換元件（與 ArticleDataAssembler 共用；M6-b 以 series_uuid 欄位取代後移除；內部以 ObjectProvider 延遲取得
     * SeriesFacade，避開 SeriesFacadeImpl → ArticleFacade 的 bean 循環，本類別不得直接注入 SeriesFacade）
     */
    private final ArticleIdentityResolver identityResolver;

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
                    UUID.randomUUID(),
                    article.getUuid(),
                    article.getAuthorUuid(),
                    action,
                    Instant.now());
            rabbitTemplate.convertAndSend(
                    ArticleEventContract.EXCHANGE,
                    ArticleEventContract.ROUTING_KEY_CONTENT_CHANGED,
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
                    UUID.randomUUID(),
                    article.getUuid(),
                    article.getAuthorUuid(),
                    article.getTitle(),
                    article.getPublishedAt(),
                    article.getSlug(),
                    article.getSummary(),
                    stripMarkdown(article.getContent()),
                    resolveAuthorNickname(article),
                    tags);
            rabbitTemplate.convertAndSend(
                    ArticleEventContract.EXCHANGE,
                    ArticleEventContract.ROUTING_KEY_UPDATED,
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
                    UUID.randomUUID(),
                    article.getUuid(),
                    article.getAuthorUuid(),
                    article.getTitle(),
                    article.getPublishedAt(),
                    article.getSlug(),
                    article.getSummary(),
                    stripMarkdown(article.getContent()),
                    resolveAuthorNickname(article),
                    tags);
            rabbitTemplate.convertAndSend(
                    ArticleEventContract.EXCHANGE,
                    ArticleEventContract.ROUTING_KEY_PUBLISHED,
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
     * <p><b>失敗一律 ERROR（與 {@link #publishArchived(Article)} 同一判準）</b>：
     * 這條事件是「把文章從 ES 索引移除」的唯一觸發（另有 series 模組訂閱以遞減 article_count）。
     * 送不出去就會留下一筆搜尋得到、點進去 404 的幽靈 document，而該 document 本身
     * 還帶著 title / summary / content。「端點回 200 刪除成功」與「文章仍搜尋得到」
     * 同時成立而無人察覺，正是 warn 蓋不住的狀態；訊息帶 eventId 與 articleUuid，
     * 讓維運可告警並人工補送。</p>
     *
     * @param article      文章 entity（提供 uuid / authorUuid）
     * @param seriesUuid   文章所屬 series 公開 UUID（nullable；caller 必須在 delete <b>之前</b>以
     *                     {@link #resolveSeriesUuidForDelete} 取得，本方法不再查 series——
     *                     它是 series 模組扣計數的唯一依據，不能在刪除後才查、查失敗就發出 null）
     * @param categoryIds  文章 categories（caller 在 delete 前讀取；空 list 不可 null）
     * @param tagIds       文章 tags（caller 在 delete 前讀取；空 list 不可 null）
     */
    public void publishDeleted(Article article, UUID seriesUuid,
                               List<UUID> categoryIds, List<UUID> tagIds) {
        UUID eventId = UUID.randomUUID();
        try {
            ArticleDeletedEvent event = new ArticleDeletedEvent(
                eventId,
                article.getUuid(),
                categoryIds != null ? categoryIds : List.of(),
                tagIds != null ? tagIds : List.of(),
                Instant.now(),
                article.getAuthorUuid(),
                seriesUuid
            );
            rabbitTemplate.convertAndSend(
                    ArticleEventContract.EXCHANGE,
                    ArticleEventContract.ROUTING_KEY_DELETED,
                    event);
        } catch (Exception e) {
            log.error("文章刪除事件發送失敗，Elasticsearch 索引可能未移除、series 計數可能未遞減，"
                            + "需人工補送：articleUuid={}, eventId={}, error={}",
                    article.getUuid(), eventId, e.getMessage(), e);
        }
    }

    /**
     * 於<b>刪除文章之前</b>取得其所屬 series 的公開 UUID，供 {@link #publishDeleted} 帶入事件。
     *
     * <p>為什麼必須在刪除前、且失敗要往外拋：{@code ArticleDeletedEvent} 不再帶 Long {@code seriesId}，
     * {@code seriesUuid} 成為 series 模組扣 {@code article_count} 的唯一依據。若等文章刪除之後才查、
     * 查詢又失敗，事件就會帶著 null 發出，series 的計數永遠不會被扣。改成刪除前取得：查詢失敗時
     * 刪除根本不動手，使用者看到錯誤可重試；成功時 uuid 一路傳給 publisher，事件發送前不再查詢。</p>
     *
     * @param seriesId series 主鍵；null（文章不在 series）回傳 null 且不發任何查詢
     * @return series 公開 UUID；查無（資料異常）為 null
     * @throws RuntimeException 查詢失敗時直接往外拋（不吞掉）
     */
    public UUID resolveSeriesUuidForDelete(Long seriesId) {
        if (seriesId == null) {
            return null;
        }
        return identityResolver.resolveSeriesUuids(List.of(seriesId)).get(seriesId);
    }

    // ──────────────────────────────────────────────
    // Archived（文章下架，PUBLISHED → ARCHIVED）
    // ──────────────────────────────────────────────

    /**
     * 發送文章下架事件至 RabbitMQ（供搜尋模組移除 Elasticsearch 索引）。
     *
     * <p>僅在文章狀態已轉為 ARCHIVED 且 DB 已 commit 後才應呼叫；
     * 與其他事件一致採 best-effort，發送失敗不往外拋、不影響已 commit 的下架結果。</p>
     *
     * <p><b>失敗一律 ERROR（與其他 best-effort 事件的 warn 不同）</b>：下架用於法務／侵權撤下，
     * 而移除 ES 索引是唯一的撤下機制，consumer 端失敗又是 requeue=false 直入 DLQ。
     * 若這裡只留 warn，「端點回 200 下架成功」與「文章仍搜尋得到」會同時成立而無人察覺。
     * 訊息帶 eventId 與 articleUuid，讓維運可告警並人工補送。</p>
     *
     * @param article 已下架的文章實體
     */
    public void publishArchived(Article article) {
        UUID eventId = UUID.randomUUID();
        try {
            ArticleArchivedEvent event = new ArticleArchivedEvent(
                    eventId,
                    article.getUuid(),
                    Instant.now());
            rabbitTemplate.convertAndSend(
                    ArticleEventContract.EXCHANGE,
                    ArticleEventContract.ROUTING_KEY_ARCHIVED,
                    event);
        } catch (Exception e) {
            log.error("文章下架事件發送失敗，Elasticsearch 索引可能未移除，需人工補送："
                            + "articleUuid={}, eventId={}, error={}",
                    article.getUuid(), eventId, e.getMessage(), e);
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
                    ArticleEventContract.EXCHANGE,
                    ArticleEventContract.ROUTING_KEY_TAGGED,
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
                    ArticleEventContract.EXCHANGE,
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
     * 取得作者暱稱（事件的 {@code authorNickname} 欄位）。
     *
     * <p>經 {@code UserFacade.getDisplayInfoByUuids} 以作者 UUID 查詢（不依使用者狀態過濾）；
     * 查無作者時回傳 null。查詢失敗時例外往外拋，由呼叫端的 best-effort try／catch 記錄並不發送該事件
     * （與先前以 Long 版 facade 查詢時的行為相同）。</p>
     *
     * @param article 事件所屬文章
     * @return 作者暱稱；查無為 null
     */
    private String resolveAuthorNickname(Article article) {
        UUID authorUuid = article.getAuthorUuid();
        if (authorUuid == null) {
            return null;
        }
        UserDisplayInfo author = userFacade.getDisplayInfoByUuids(List.of(authorUuid)).get(authorUuid);
        return author == null ? null : author.nickname();
    }

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
