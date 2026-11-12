package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.model.Article;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleDataAssembler} 單元測試。
 *
 * <p>M6 之後 ArticleData 的 {@code authorUuid}（M6-a）與 {@code seriesUuid}（M6-b）都<b>直接取自
 * {@code articles} 的欄位</b>：不再經 users 或 series 查詢、也沒有任何 Long 的跨模組主鍵，
 * 因此本元件沒有任何依賴，也不會發出任何查詢（批次或逐筆皆無）。</p>
 *
 * @author Yuan
 * @version 3.0（M6-b：seriesUuid 直接取自 articles.series_uuid）
 */
@DisplayName("ArticleDataAssembler 單元測試")
class ArticleDataAssemblerTest {

    private final ArticleDataAssembler assembler = new ArticleDataAssembler();

    private static Article article(long id, UUID authorUuid, UUID seriesUuid) {
        Article a = new Article();
        a.setId(id);
        a.setUuid(UUID.randomUUID());
        a.setAuthorUuid(authorUuid);
        a.setStatus(ArticleStatus.PUBLISHED);
        a.setSeriesUuid(seriesUuid);
        a.setSeriesPosition(seriesUuid == null ? null : 1);
        return a;
    }

    @Test
    @DisplayName("單篇文章 → authorUuid 與 seriesUuid 直接取自文章，seriesPosition 與狀態原樣帶出")
    void toArticleData_singleArticle_copiesUuidsAndFieldsFromArticle() {
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();
        Article source = article(1L, authorUuid, seriesUuid);

        ArticleData data = assembler.toArticleData(source);

        assertThat(data.uuid()).isEqualTo(source.getUuid());
        assertThat(data.status()).isEqualTo("PUBLISHED");
        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isEqualTo(seriesUuid);
        assertThat(data.seriesPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("文章無系列 → seriesUuid 與 seriesPosition 皆為 null")
    void toArticleData_noSeries_seriesFieldsNull() {
        UUID authorUuid = UUID.randomUUID();

        ArticleData data = assembler.toArticleData(article(1L, authorUuid, null));

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isNull();
        assertThat(data.seriesPosition()).isNull();
    }

    @Test
    @DisplayName("多篇文章 → 逐篇對應各自的作者與系列 uuid，保留輸入順序")
    void toArticleDataList_multipleArticles_mapsEachInOrder() {
        UUID authorA = UUID.randomUUID();
        UUID authorB = UUID.randomUUID();
        UUID seriesX = UUID.randomUUID();
        UUID seriesY = UUID.randomUUID();
        List<Article> articles = List.of(
                article(1L, authorA, seriesX),
                article(2L, authorA, seriesX),
                article(3L, authorB, seriesY),
                article(4L, authorB, null));

        List<ArticleData> result = assembler.toArticleDataList(articles);

        assertThat(result).extracting(ArticleData::authorUuid).containsExactly(authorA, authorA, authorB, authorB);
        assertThat(result).extracting(ArticleData::seriesUuid).containsExactly(seriesX, seriesX, seriesY, null);
        assertThat(result).extracting(ArticleData::uuid)
                .containsExactlyElementsOf(articles.stream().map(Article::getUuid).toList());
    }

    @Test
    @DisplayName("系列已被刪除但事件尚未清空歸屬 → 仍帶出文章目前儲存的 seriesUuid（不再有『查無即 null』的轉換）")
    void toArticleDataList_staleSeriesUuid_isPassedThrough() {
        UUID staleSeries = UUID.randomUUID();

        List<ArticleData> result = assembler.toArticleDataList(List.of(article(1L, UUID.randomUUID(), staleSeries)));

        assertThat(result.get(0).seriesUuid()).isEqualTo(staleSeries);
    }

    @Test
    @DisplayName("空輸入 → 回傳空清單")
    void toArticleDataList_empty_returnsEmpty() {
        assertThat(assembler.toArticleDataList(List.of())).isEmpty();
        assertThat(assembler.toArticleDataList(null)).isEmpty();
    }
}
