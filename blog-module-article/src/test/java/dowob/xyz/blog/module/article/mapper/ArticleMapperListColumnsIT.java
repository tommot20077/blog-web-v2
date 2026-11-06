package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.PublishedArticleCriteria;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 列表查詢改為明確選欄後，對真實 PostgreSQL 驗證「非正文欄位全部照舊映射」與「詳情路徑仍取得完整內文」（PERF-06）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMapper 列表明確選欄 IT（PERF-06）")
class ArticleMapperListColumnsIT extends ArticleSeriesITSupport {

    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 5, 6, 7, 8, 9);

    @Autowired private ArticleMapper mapper;
    @Autowired private dowob.xyz.blog.module.article.repository.ArticleRepository repository;

    private UUID insertFull(UUID author, String status, UUID seriesUuid) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, summary, content_md, content_html, toc, "
                        + "cover_image_url, status, reject_reason, view_count, like_count, comment_count, "
                        + "series_uuid, series_position, published_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                uuid, author, "標題", "slug-" + uuid, "摘要", "# 內文MD", "<h1>內文HTML</h1>", "[{\"id\":\"a\"}]",
                "http://img/c.png", status, "駁回原因", 11L, 22, 33,
                seriesUuid, seriesUuid == null ? null : 5, Timestamp.valueOf(PUBLISHED_AT));
        return uuid;
    }

    private void assertSummaryFieldsMapped(Article a, UUID uuid, UUID author, String status, UUID seriesUuid) {
        assertThat(a.getId()).isNotNull();
        assertThat(a.getUuid()).isEqualTo(uuid);
        assertThat(a.getAuthorUuid()).isEqualTo(author);
        assertThat(a.getTitle()).isEqualTo("標題");
        assertThat(a.getSlug()).isEqualTo("slug-" + uuid);
        assertThat(a.getSummary()).isEqualTo("摘要");
        assertThat(a.getCoverImageUrl()).isEqualTo("http://img/c.png");
        assertThat(a.getStatus()).isEqualTo(ArticleStatus.valueOf(status));
        assertThat(a.getRejectReason()).isEqualTo("駁回原因");
        assertThat(a.getViewCount()).isEqualTo(11L);
        assertThat(a.getLikeCount()).isEqualTo(22L);
        assertThat(a.getCommentCount()).isEqualTo(33);
        assertThat(a.getVersion()).isNotNull();
        assertThat(a.getSeriesUuid()).isEqualTo(seriesUuid);
        assertThat(a.getPublishedAt()).isEqualTo(PUBLISHED_AT);
        assertThat(a.getCreatedAt()).isNotNull();
        assertThat(a.getUpdatedAt()).isNotNull();
        // 列表不讀正文
        assertThat(a.getContent()).isNull();
        assertThat(a.getContentHtml()).isNull();
        assertThat(a.getToc()).isNull();
    }

    @Test
    @DisplayName("公開列表：摘要所需欄位全部映射，正文三欄為 null")
    void findPublishedPageByCriteria_mapsSummaryFields_noBody() {
        UUID author = UUID.randomUUID();
        UUID uuid = insertFull(author, "PUBLISHED", null);

        List<Article> rows = mapper.findPublishedPageByCriteria(
                PublishedArticleCriteria.of(ArticleListQuery.unfiltered(), LocalDateTime.now()), 0L, 10);

        Article a = rows.stream().filter(r -> r.getUuid().equals(uuid)).findFirst().orElseThrow();
        assertSummaryFieldsMapped(a, uuid, author, "PUBLISHED", null);
    }

    @Test
    @DisplayName("我的文章（含／不含狀態）：摘要欄位全部映射，正文三欄為 null")
    void findByAuthor_mapsSummaryFields_noBody() {
        UUID author = UUID.randomUUID();
        UUID uuid = insertFull(author, "REJECTED", null);

        Article withStatus = mapper.findByAuthorUuidAndStatus(author, ArticleStatus.REJECTED, 0L, 10).get(0);
        Article paged = mapper.findByAuthorUuidPaged(author, 0L, 10).get(0);

        assertSummaryFieldsMapped(withStatus, uuid, author, "REJECTED", null);
        assertSummaryFieldsMapped(paged, uuid, author, "REJECTED", null);
    }

    @Test
    @DisplayName("待審列表：摘要欄位全部映射，正文三欄為 null")
    void findPendingReviewPage_mapsSummaryFields_noBody() {
        UUID author = UUID.randomUUID();
        UUID uuid = insertFull(author, "PENDING_REVIEW", null);

        Article a = mapper.findPendingReviewPage(0L, 1000).stream()
                .filter(r -> r.getUuid().equals(uuid)).findFirst().orElseThrow();

        assertSummaryFieldsMapped(a, uuid, author, "PENDING_REVIEW", null);
    }

    @Test
    @DisplayName("系列成員查詢：摘要欄位（含 series_uuid／series_position）映射，正文三欄為 null")
    void findBySeriesUuidOrderByPosition_mapsSummaryFields_noBody() {
        UUID author = UUID.randomUUID();
        UUID series = UUID.randomUUID();
        UUID uuid = insertFull(author, "PUBLISHED", series);

        Article a = mapper.findBySeriesUuidOrderByPosition(series).get(0);

        assertSummaryFieldsMapped(a, uuid, author, "PUBLISHED", series);
        assertThat(a.getSeriesPosition()).isEqualTo(5);
    }

    @Test
    @DisplayName("詳情路徑（Repository.findByUuid）仍拿得到完整正文、HTML 與 TOC")
    void detailPath_stillLoadsFullBody() {
        UUID uuid = insertFull(UUID.randomUUID(), "PUBLISHED", null);

        Article a = repository.findByUuid(uuid).orElseThrow();

        assertThat(a.getContent()).isEqualTo("# 內文MD");
        assertThat(a.getContentHtml()).isEqualTo("<h1>內文HTML</h1>");
        assertThat(a.getToc()).isEqualTo("[{\"id\":\"a\"}]");
    }
}
