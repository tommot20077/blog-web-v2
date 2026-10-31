package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.model.Article;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * 將 {@link Article} 實體組裝為跨模組 {@link ArticleData} 的元件。
 *
 * <p>{@code authorUuid}（M6-a）與 {@code seriesUuid}（M6-b）都<b>直接取自 {@code articles} 的欄位</b>
 * （{@code author_uuid}、{@code series_uuid}）：不再經 users／series 查詢，也沒有任何 Long 的跨模組主鍵，
 * 因此本元件沒有任何依賴、不發任何查詢。集中在這裡是為了讓 {@code ArticleFacadeImpl} 與
 * {@code ArticleLookupFacadeImpl} 不必各寫一份轉換。</p>
 *
 * <p><b>依賴限制</b>：本類別不得注入任何 service／facade／mapper／Provider，
 * 才能被 {@code ArticleLookupFacadeImpl} 這個「最小依賴」bean 安全使用（不會重新引入 article ⇄ file 循環）。</p>
 *
 * <p>系列剛被刪除、刪除事件尚未清空歸屬時，{@code seriesUuid} 仍是文章儲存的值（最終一致）；
 * 事件或每日校正會把它清空。</p>
 *
 * @author Yuan
 * @version 3.0（M6-b：seriesUuid 直接取自 articles.series_uuid，移除轉換元件）
 */
@Component
public class ArticleDataAssembler {

    /**
     * 組裝單篇文章的 {@link ArticleData}（authorUuid / seriesUuid 直接取自文章）。
     *
     * @param article 文章實體
     * @return ArticleData
     */
    public ArticleData toArticleData(Article article) {
        return new ArticleData(
                article.getUuid(),
                article.getStatus() != null ? article.getStatus().name() : null,
                article.getSeriesPosition(),
                article.getAuthorUuid(),
                article.getSeriesUuid());
    }

    /**
     * 批次組裝多篇文章的 {@link ArticleData}，保留輸入順序。
     *
     * @param articles 文章實體集合；null 或空集合回傳空清單
     * @return 與輸入同順序的 ArticleData 清單
     */
    public List<ArticleData> toArticleDataList(Collection<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        return articles.stream().map(this::toArticleData).toList();
    }
}
