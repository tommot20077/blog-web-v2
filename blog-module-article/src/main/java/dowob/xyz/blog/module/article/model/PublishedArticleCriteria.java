package dowob.xyz.blog.module.article.model;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 公開文章列表的 mapper 查詢條件。
 *
 * <p>與 {@link ArticleListQuery} 分開的理由：API 參數是「最近 N 天」，SQL 需要的是具體時間點；
 * 換算時的「現在」由呼叫端傳入而不在此取系統時鐘，換算邏輯因此是純函式、可決定性測試。</p>
 *
 * <p>刻意採 JavaBean getter 而非 record：MyBatis 動態 SQL 的 {@code <if test>} 以 OGNL
 * 解析屬性，JavaBean getter 是其最確定支援的形狀。</p>
 *
 * <p>「現在」與 {@code articles.published_at} 同為 JVM 時區的 {@link LocalDateTime}
 * （發布時以 {@code LocalDateTime.now()} 寫入），故在 Java 端換算而不用資料庫的 {@code NOW()}——
 * 後者受 DB session 時區影響，兩端時區不同時會偏移數小時。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
public final class PublishedArticleCriteria {

    /** 標籤 slug（AND 語意），已去重 */
    private final List<String> tagSlugs;

    /** 去重後的標籤數，供 {@code HAVING COUNT(DISTINCT ...) = tagCount} 判斷「全部命中」 */
    private final int tagCount;

    /** 分類 slug（OR 語意） */
    private final List<String> categorySlugs;

    /** 作者公開 UUID（OR 語意） */
    private final List<UUID> authorUuids;

    /** 只取此時間點（含）之後發布者；{@code null} 表示不篩選 */
    private final LocalDateTime publishedAfter;

    /** 排序鍵，見 {@link dowob.xyz.blog.module.article.model.dto.request.ArticleListSort#key()} */
    private final String sortKey;

    /**
     * 建立查詢條件。
     *
     * @param tagSlugs       標籤 slug
     * @param categorySlugs  分類 slug
     * @param authorUuids    作者 UUID
     * @param publishedAfter 發布時間下限
     * @param sortKey        排序鍵
     */
    private PublishedArticleCriteria(List<String> tagSlugs,
                                     List<String> categorySlugs,
                                     List<UUID> authorUuids,
                                     LocalDateTime publishedAfter,
                                     String sortKey) {
        this.tagSlugs = tagSlugs;
        this.tagCount = tagSlugs.size();
        this.categorySlugs = categorySlugs;
        this.authorUuids = authorUuids;
        this.publishedAfter = publishedAfter;
        this.sortKey = sortKey;
    }

    /**
     * 由 API 層參數建立查詢條件。
     *
     * <p>本方法是公開列表通往 SQL 的唯一入口（建構子為 private），多值篩選的上限在此強制：
     * 任一清單超過 {@link ArticleListQuery#MAX_VALUES_PER_FILTER} 即拒絕，
     * 經 {@code GlobalExceptionHandler} 回 400。</p>
     *
     * @param query 已正規化的篩選參數
     * @param now   換算日期區間用的「現在」
     * @return 查詢條件
     * @throws BusinessException 任一多值篩選超過上限時（{@link ArticleErrorCode#ARTICLE_LIST_FILTER_TOO_MANY_VALUES}）
     */
    public static PublishedArticleCriteria of(ArticleListQuery query, LocalDateTime now) {
        Objects.requireNonNull(query, "query 不得為 null");
        Objects.requireNonNull(now, "now 不得為 null");
        int limit = ArticleListQuery.MAX_VALUES_PER_FILTER;
        if (query.tags().size() > limit
                || query.categorySlug().size() > limit
                || query.authorUuids().size() > limit) {
            throw new BusinessException(ArticleErrorCode.ARTICLE_LIST_FILTER_TOO_MANY_VALUES);
        }
        LocalDateTime publishedAfter = query.publishedWithinDays() == null
                ? null
                : now.minusDays(query.publishedWithinDays());
        return new PublishedArticleCriteria(
                query.tags(),
                query.categorySlug(),
                query.authorUuids(),
                publishedAfter,
                query.sortOrder().key());
    }
}
