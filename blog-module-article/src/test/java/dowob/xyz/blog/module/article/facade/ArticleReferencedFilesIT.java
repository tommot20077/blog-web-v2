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
}
