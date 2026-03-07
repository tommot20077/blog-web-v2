package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ArticleFacade 實作
 *
 * <p>
 * 提供搜尋模組與推薦模組所需的文章資料跨模組存取實作。
 * 搜尋功能透過 {@link ArticleMapper} 與 {@link UserFacade} 取得全量索引資料；
 * 推薦功能透過 {@link ArticleRecommendMapper} 執行高效查詢。
 * 此 Bean 由 blog-module-article 提供，在應用啟動時注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
@RequiredArgsConstructor
public class ArticleFacadeImpl implements ArticleFacade {

    /**
     * 文章 MyBatis Mapper（搜尋用）
     */
    private final ArticleMapper articleMapper;

    /**
     * 用戶 Facade（跨模組查詢作者資訊）
     */
    private final UserFacade userFacade;

    /**
     * 推薦功能專用 MyBatis Mapper
     */
    private final ArticleRecommendMapper recommendMapper;

    /**
     * 查詢所有已發布文章的索引資料，供搜尋模組重建 Elasticsearch 索引使用
     *
     * @return 已發布文章的索引資料列表
     */
    @Override
    public List<ArticleIndexData> findAllPublishedForIndex() {
        return articleMapper.findAllPublished().stream()
                .map(this::toIndexData)
                .toList();
    }

    /**
     * {@inheritDoc}
     *
     * <p>分兩步查詢：先確認文章存在且已發布，再取得標籤 ID 列表。</p>
     */
    @Override
    public Optional<ArticleBasicInfo> getPublishedArticleBasicInfo(UUID articleUuid) {
        Long articleId = recommendMapper.findPublishedIdByUuid(articleUuid);
        if (articleId == null) {
            return Optional.empty();
        }
        List<Long> tagIds = recommendMapper.findTagIdsByArticleId(articleId);
        return Optional.of(new ArticleBasicInfo(articleUuid, tagIds));
    }

    /**
     * {@inheritDoc}
     *
     * <p>若 tagIds 為空則直接回傳空列表，避免 SQL IN () 語法錯誤。</p>
     */
    @Override
    public List<ArticleSummaryInfo> getArticlesByTagIds(List<Long> tagIds, UUID excludeUuid, int limit) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<ArticleSummaryRow> rows = recommendMapper.findByTagIds(tagIds, excludeUuid, limit);
        return assembleSummaryInfoList(rows);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleSummaryInfo> getRecentPublishedArticles(UUID excludeUuid, int limit) {
        List<ArticleSummaryRow> rows = recommendMapper.findRecentPublished(excludeUuid, limit);
        return assembleSummaryInfoList(rows);
    }

    /**
     * {@inheritDoc}
     *
     * <p>若 uuids 為空則直接回傳空列表，避免 SQL IN () 語法錯誤。</p>
     */
    @Override
    public List<ArticleSummaryInfo> getPublishedArticlesByUuids(List<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return Collections.emptyList();
        }
        List<ArticleSummaryRow> rows = recommendMapper.findByUuids(uuids);
        return assembleSummaryInfoList(rows);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleTrendingData> getArticlesPublishedAfter(LocalDateTime since) {
        return recommendMapper.findPublishedAfter(since);
    }

    /**
     * 將文章實體轉換為索引資料 DTO（供搜尋模組使用）
     *
     * @param article 文章實體
     * @return ArticleIndexData
     */
    private ArticleIndexData toIndexData(Article article) {
        List<TagInfo> tags = articleMapper.findTagsByArticleUuid(article.getUuid());
        List<ArticleIndexData.TagData> tagData = tags.stream()
                .map(t -> new ArticleIndexData.TagData(t.id(), t.name(), t.slug()))
                .toList();

        return new ArticleIndexData(
                article.getUuid(),
                article.getTitle(),
                article.getSlug(),
                article.getSummary(),
                stripMarkdown(article.getContent()),
                article.getAuthorId(),
                userFacade.getUserUsernameById(article.getAuthorId()).orElse(null),
                userFacade.getUserNicknameById(article.getAuthorId()).orElse(null),
                article.getPublishedAt(),
                article.getViewCount(),
                article.getLikeCount(),
                tagData);
    }

    /**
     * 去除 Markdown 格式，回傳純文字（供 Elasticsearch 索引）
     *
     * @param markdown Markdown 原文
     * @return 純文字內容
     */
    private String stripMarkdown(String markdown) {
        if (markdown == null) return "";
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

    /**
     * 將 ArticleSummaryRow 列表組裝為 ArticleSummaryInfo 列表
     *
     * <p>
     * 批次查詢所有文章的標籤名稱，避免 N+1 查詢問題，
     * 再以 articleId 為索引建立 Map 對應。
     * </p>
     *
     * @param rows 原始查詢結果列表
     * @return 組裝完成的 ArticleSummaryInfo 列表
     */
    private List<ArticleSummaryInfo> assembleSummaryInfoList(List<ArticleSummaryRow> rows) {
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Long> articleIds = rows.stream().map(ArticleSummaryRow::getId).toList();
        List<ArticleTagRow> tagRows = recommendMapper.findTagsByArticleIds(articleIds);

        Map<Long, List<String>> tagNamesByArticleId = tagRows.stream()
                .collect(Collectors.groupingBy(
                        ArticleTagRow::getArticleId,
                        Collectors.mapping(ArticleTagRow::getTagName, Collectors.toList())
                ));

        return rows.stream()
                .map(row -> new ArticleSummaryInfo(
                        row.getUuid(),
                        row.getTitle(),
                        row.getSlug(),
                        row.getSummary(),
                        row.getAuthorNickname(),
                        tagNamesByArticleId.getOrDefault(row.getId(), Collections.emptyList()),
                        row.getViewCount(),
                        row.getLikeCount(),
                        row.getPublishedAt()
                ))
                .toList();
    }
}
