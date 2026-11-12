package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleLookupFacade#findFileUuidsReferencedByRetainedArticles} 整合測試（真 Postgres，驗證正規表示式 SQL）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleLookupFacade 保留文章引用的檔案 整合測試")
class ArticleReferencedFilesIT extends ArticleSeriesITSupport {

    @Autowired private ArticleLookupFacade articleLookupFacade;

    private static String contentRef(UUID file) {
        return "![圖](/api/v1/files/" + file + "/content)";
    }

    private void insertArticle(UUID author, String status, String content, String cover) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, cover_image_url, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                uuid, author, "t-" + uuid, "slug-" + uuid, content, cover, status);
    }

    @Test
    @DisplayName("已發布／已下架文章的內文與封面引用的檔案都被收集；草稿、他人文章的引用不算")
    void collectsContentAndCoverRefsOfRetainedArticlesOnly() {
        UUID author = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID inPublishedContent = UUID.randomUUID();
        UUID publishedCover = UUID.randomUUID();
        UUID inArchivedContent = UUID.randomUUID();
        UUID onlyInDraft = UUID.randomUUID();
        UUID inOthersArticle = UUID.randomUUID();
        UUID upperCased = UUID.randomUUID();
        insertArticle(author, "PUBLISHED", "前文 " + contentRef(inPublishedContent) + " 與 "
                + contentRef(inPublishedContent) + " 與大寫 /api/v1/files/" + upperCased.toString().toUpperCase() + "/content",
                "/api/v1/files/" + publishedCover + "/content");
        insertArticle(author, "ARCHIVED", contentRef(inArchivedContent), null);
        insertArticle(author, "DRAFT", contentRef(onlyInDraft), "/api/v1/files/" + onlyInDraft + "/content");
        insertArticle(other, "PUBLISHED", contentRef(inOthersArticle), null);

        assertThat(articleLookupFacade.findFileUuidsReferencedByRetainedArticles(author))
                .containsExactlyInAnyOrder(inPublishedContent, publishedCover, inArchivedContent, upperCased);
    }

    @Test
    @DisplayName("沒有保留文章或沒有引用任何檔案：空集合")
    void returnsEmptyWhenNothingReferenced() {
        UUID author = UUID.randomUUID();
        insertArticle(author, "PUBLISHED", "純文字沒有圖", null);

        assertThat(articleLookupFacade.findFileUuidsReferencedByRetainedArticles(author)).isEmpty();
        assertThat(articleLookupFacade.findFileUuidsReferencedByRetainedArticles(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("findFileUuidsReferencedByAuthorArticles：不分狀態——草稿、待審、已發布的內文與封面引用都收集；他人文章不算")
    void authorArticles_collectsReferencesOfEveryStatus() {
        UUID author = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID inDraft = UUID.randomUUID();
        UUID draftCover = UUID.randomUUID();
        UUID inPending = UUID.randomUUID();
        UUID inPublished = UUID.randomUUID();
        UUID inOthers = UUID.randomUUID();
        insertArticle(author, "DRAFT", contentRef(inDraft), "/api/v1/files/" + draftCover + "/content");
        insertArticle(author, "PENDING_REVIEW", contentRef(inPending), null);
        insertArticle(author, "PUBLISHED", contentRef(inPublished) + contentRef(inPublished), null);
        insertArticle(other, "DRAFT", contentRef(inOthers), null);

        assertThat(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(author))
                .containsExactlyInAnyOrder(inDraft, draftCover, inPending, inPublished);
        assertThat(articleLookupFacade.findFileUuidsReferencedByAuthorArticles(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("filterExistingUuids：不分狀態回傳仍存在者，已不存在與 null 被排除")
    void filterExistingUuids_returnsExistingRegardlessOfStatus() {
        UUID author = UUID.randomUUID();
        insertArticle(author, "DRAFT", "x", null);
        insertArticle(author, "PUBLISHED", "y", null);
        java.util.List<UUID> real = jdbc.queryForList("SELECT uuid FROM articles WHERE author_uuid = ?", UUID.class, author);
        UUID gone = UUID.randomUUID();
        java.util.List<UUID> query = new java.util.ArrayList<>(real);
        query.add(gone);
        query.add(null);

        assertThat(articleLookupFacade.filterExistingUuids(query)).containsExactlyInAnyOrderElementsOf(real);
        assertThat(articleLookupFacade.filterExistingUuids(java.util.List.of())).isEmpty();
    }
}
