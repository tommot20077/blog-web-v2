package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 全量重建的內文來源（真實 PostgreSQL）。
 *
 * <p>{@code findAllPublished} 走 MyBatis，{@code content_md} 欄位原本因名稱對不上實體的 {@code content}
 * 而恆為 null，導致全量重建把每篇文章的內文索引成空字串。本測試鎖定：實體的 content 等於資料庫的
 * {@code content_md}，且送進索引的 {@code contentText} 含正文關鍵字。</p>
 */
@DisplayName("全量重建索引的內文來源 IT")
class ArticleIndexContentIT extends ArticleSeriesITSupport {

    @Autowired private ArticleMapper mapper;
    @Autowired private ArticleFacade articleFacade;

    private UUID insertPublished(String md) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, content_html, status, published_at) "
                        + "VALUES (?, ?, 't', ?, ?, '<p>x</p>', 'PUBLISHED', now())",
                uuid, UUID.randomUUID(), "slug-" + uuid, md);
        return uuid;
    }

    @Test
    @DisplayName("findAllPublished：content 非 null 且等於 content_md")
    void findAllPublished_contentEqualsContentMd() {
        UUID uuid = insertPublished("# 標題\n\n獨一無二的關鍵字 zebrafish-42");

        Article a = mapper.findAllPublished().stream().filter(x -> x.getUuid().equals(uuid)).findFirst().orElseThrow();

        assertThat(a.getContent()).isEqualTo("# 標題\n\n獨一無二的關鍵字 zebrafish-42");
    }

    @Test
    @DisplayName("findAllPublishedForIndex：contentText 非空且含正文關鍵字")
    void findAllPublishedForIndex_contentTextContainsBodyKeyword() {
        UUID uuid = insertPublished("前言\n\n只有正文才有的詞 quokka-77");

        List<ArticleIndexData> data = articleFacade.findAllPublishedForIndex();

        ArticleIndexData row = data.stream().filter(d -> d.articleUuid().equals(uuid)).findFirst().orElseThrow();
        assertThat(row.contentText()).isNotBlank().contains("quokka-77");
    }
}
