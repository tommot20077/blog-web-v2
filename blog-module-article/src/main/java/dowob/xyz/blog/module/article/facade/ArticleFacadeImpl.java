package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleRestoreData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleTrendingData;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.module.article.event.ArticleContentChangedEvent;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.mapper.ArticleRecommendMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.ArticleSummaryRow;
import dowob.xyz.blog.module.article.model.ArticleTagRow;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.service.ArticleEventPublisher;
import dowob.xyz.blog.module.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
     * 文章 Service（SP-B 新增：供 10 個新 delegate method 使用）
     */
    private final ArticleService articleService;

    /**
     * 文章 Repository（SP-D 新增：供 findContentById / applyRestoreContent 使用）
     */
    private final ArticleRepository articleRepository;

    /**
     * 標籤 Facade（SP-D 新增：供 applyRestoreContent syncArticleTags 使用）
     */
    private final TagFacade tagFacade;

    /**
     * 文章事件發布元件（SP-D 新增：供 applyRestoreContent publish events 使用）
     */
    private final ArticleEventPublisher articleEventPublisher;

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
        List<UUID> tagIds = recommendMapper.findTagIdsByArticleUuid(articleUuid)
                .stream().map(UUID::fromString).collect(Collectors.toList());
        return Optional.of(new ArticleBasicInfo(articleUuid, tagIds));
    }

    /**
     * {@inheritDoc}
     *
     * <p>若 tagIds 為空則直接回傳空列表，避免 SQL IN () 語法錯誤。</p>
     */
    @Override
    public List<ArticleSummaryInfo> getArticlesByTagIds(List<UUID> tagIds, UUID excludeUuid, int limit) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tagIdStrings = tagIds.stream().map(UUID::toString).collect(Collectors.toList());
        List<ArticleSummaryRow> rows = recommendMapper.findByTagIds(tagIdStrings, excludeUuid, limit);
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

        List<String> articleUuidStrings = rows.stream()
                .map(ArticleSummaryRow::getUuid).collect(Collectors.toList());
        List<ArticleTagRow> tagRows = recommendMapper.findTagsByArticleUuids(articleUuidStrings);

        Map<String, List<String>> tagNamesByArticleUuid = tagRows.stream()
                .collect(Collectors.groupingBy(
                        ArticleTagRow::getArticleUuid,
                        Collectors.mapping(ArticleTagRow::getTagName, Collectors.toList())
                ));

        return rows.stream()
                .map(row -> new ArticleSummaryInfo(
                        UUID.fromString(row.getUuid()),
                        row.getTitle(),
                        row.getSlug(),
                        row.getSummary(),
                        row.getAuthorNickname(),
                        tagNamesByArticleUuid.getOrDefault(row.getUuid(), Collections.emptyList()),
                        row.getViewCount(),
                        row.getLikeCount(),
                        row.getPublishedAt()
                ))
                .toList();
    }

    // ─── SP-B 新增 5 read method（純 delegate + Article→ArticleData 轉換）───

    /**
     * {@inheritDoc}
     */
    @Override
    public Long findIdByUuid(UUID articleUuid) {
        return articleService.findIdByUuid(articleUuid);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ArticleData> findByUuid(UUID articleUuid) {
        return articleService.findByUuid(articleUuid).map(this::toArticleData);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Optional<ArticleData> findById(Long articleId) {
        return articleService.findById(articleId).map(this::toArticleData);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleData> findByIds(List<Long> articleIds) {
        return articleService.findByIds(articleIds).stream()
                .map(this::toArticleData)
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ArticleData> findBySeriesIdOrderByPosition(Long seriesId) {
        return articleService.findBySeriesIdOrderByPosition(seriesId).stream()
                .map(this::toArticleData)
                .toList();
    }

    // ─── SP-B 新增 5 write method（純 delegate）───

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementCommentCount(Long articleId) {
        articleService.incrementCommentCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementCommentCount(Long articleId) {
        articleService.decrementCommentCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void incrementLikeCount(Long articleId) {
        articleService.incrementLikeCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void decrementLikeCount(Long articleId) {
        articleService.decrementLikeCount(articleId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateSeriesAssignment(Long articleId, Long seriesId, Integer seriesPosition) {
        articleService.updateSeriesAssignment(articleId, seriesId, seriesPosition);
    }

    /**
     * 將文章實體轉換為跨模組 ArticleData DTO
     *
     * <p>status 使用 {@code .name()} 字串化，避免跨模組直接 import article enum。</p>
     *
     * @param article 文章實體
     * @return ArticleData record
     */
    private ArticleData toArticleData(Article article) {
        return new ArticleData(
                article.getId(),
                article.getUuid(),
                article.getAuthorId(),
                article.getStatus() != null ? article.getStatus().name() : null,
                article.getSeriesId(),
                article.getSeriesPosition()
        );
    }

    // ─── SP-D 新增：findContentById + applyRestoreContent ───

    /**
     * {@inheritDoc}
     *
     * <p>純 delegate：委派 articleService.findById 查找 article entity，轉換為 ArticleContentData record。</p>
     */
    @Override
    public Optional<ArticleContentData> findContentById(Long articleId) {
        return articleService.findById(articleId).map(this::toContentData);
    }

    /**
     * {@inheritDoc}
     *
     * <p>atomic flow：撈 article → mutate 7 欄位 → save → syncArticleTags → publish events。</p>
     *
     * <p>重要：syncArticleTags 必須在 publish events 之前完成，
     * 確保 search index update consumer 拿到的 tags 已是最新狀態。</p>
     */
    @Override
    @Transactional
    public void applyRestoreContent(Long articleId, ArticleRestoreData data) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new BusinessException(ArticleErrorCode.ARTICLE_NOT_FOUND));

        article.setTitle(data.title());
        article.setSlug(data.slug());
        article.setContent(data.content());
        article.setSummary(data.summary());
        article.setCoverImageUrl(data.coverImageUrl());
        if (data.status() != null) {
            article.setStatus(ArticleStatus.valueOf(data.status()));
        }
        article.setContentHtml(data.contentHtml());

        Article saved = articleRepository.save(article);

        // IMPORTANT: syncArticleTags 必須在 publish events 之前發 — search index update 需拿到正確 tags
        tagFacade.syncArticleTags(saved.getUuid(),
                data.tags() != null ? data.tags() : List.of());

        articleEventPublisher.publishContentChanged(saved, ArticleContentChangedEvent.Action.RESTORED);
        if (saved.getStatus() == ArticleStatus.PUBLISHED) {
            articleEventPublisher.publishUpdated(saved);
        }
    }

    /**
     * 將文章實體轉換為跨模組 ArticleContentData DTO（SP-D 新增）
     *
     * <p>比 toArticleData 多含 title / slug / content / summary / coverImageUrl 5 個欄位，
     * 適用需要完整 article 內容的場景（version snapshot / restore）。</p>
     *
     * @param article 文章實體
     * @return ArticleContentData record
     */
    private ArticleContentData toContentData(Article article) {
        return new ArticleContentData(
                article.getId(),
                article.getUuid(),
                article.getAuthorId(),
                article.getTitle(),
                article.getSlug(),
                article.getContent(),
                article.getSummary(),
                article.getCoverImageUrl(),
                article.getStatus() != null ? article.getStatus().name() : null
        );
    }
}
