package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 將 {@link Article} 實體組裝為跨模組 {@link ArticleData} 的元件（設計 §3.1「過渡 API」）。
 *
 * <p>{@code authorUuid} 自 M6-a 起直接取自 {@code articles.author_uuid}（不再查詢）；
 * {@code seriesUuid}（M6-b 才改為直接讀欄位）集中在這裡處理，讓 {@code ArticleFacadeImpl} 與
 * {@code ArticleLookupFacadeImpl} 不必各寫一份，也保證<b>批次轉換一次取得、不逐筆查詢</b>。轉換委派給
 * {@link ArticleIdentityResolver}（與 {@code ArticleEventPublisher} 共用；SeriesFacade 的 ObjectProvider
 * 延遲取得與 bean 循環說明見該類別）。</p>
 *
 * <p><b>依賴限制</b>：本類別只依賴 {@link ArticleIdentityResolver}（其本身只依賴 ObjectProvider），
 * 因此可被 {@code ArticleLookupFacadeImpl} 這個「最小依賴」bean 安全使用（不會重新引入 article ⇄ file 循環）。</p>
 *
 * @author Yuan
 * @version 2.0（M6-a：authorUuid 直接取自 articles.author_uuid）
 */
@Component
public class ArticleDataAssembler {

    /** series uuid 的批次轉換元件（M6-b 以 series_uuid 欄位取代後移除） */
    private final ArticleIdentityResolver identityResolver;

    /**
     * 建構子。
     *
     * @param identityResolver series uuid 的批次轉換元件
     */
    public ArticleDataAssembler(ArticleIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    /**
     * 組裝單篇文章的 {@link ArticleData}（authorUuid / seriesUuid 已填值）。
     *
     * @param article 文章實體
     * @return ArticleData
     */
    public ArticleData toArticleData(Article article) {
        return toArticleDataList(List.of(article)).get(0);
    }

    /**
     * 批次組裝多篇文章的 {@link ArticleData}。
     *
     * <p>作者 uuid 直接取自文章；系列<b>只查一次</b>（去重、忽略 null 後整批送出），保留輸入順序。
     * 無系列的文章 {@code seriesUuid} 為 null；系列在 SeriesFacade 查無時同樣為 null。</p>
     *
     * @param articles 文章實體集合；null 或空集合回傳空清單且不發任何查詢
     * @return 與輸入同順序的 ArticleData 清單
     */
    public List<ArticleData> toArticleDataList(Collection<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        Map<Long, UUID> seriesUuids = identityResolver.resolveSeriesUuids(
                articles.stream().map(Article::getSeriesId).toList());
        return articles.stream()
                .map(a -> new ArticleData(
                        a.getId(),
                        a.getUuid(),
                        a.getStatus() != null ? a.getStatus().name() : null,
                        a.getSeriesId(),
                        a.getSeriesPosition(),
                        a.getAuthorUuid(),
                        a.getSeriesId() == null ? null : seriesUuids.get(a.getSeriesId())))
                .toList();
    }

    /**
     * 批次把 series 公開 UUID 轉成資料庫主鍵（委派給 {@link ArticleIdentityResolver}，M4-a 過渡 API）。
     *
     * @param seriesUuids series 公開 UUID 集合；null 或空集合回傳空 Map 且不發查詢
     * @return series 公開 UUID → 主鍵
     */
    public Map<UUID, Long> resolveSeriesIdsByUuids(Collection<UUID> seriesUuids) {
        return identityResolver.resolveSeriesIdsByUuids(seriesUuids);
    }

    /**
     * 把單一 series 公開 UUID 轉成資料庫主鍵（委派給 {@link ArticleIdentityResolver}，M4-a 過渡 API）。
     *
     * @param seriesUuid series 公開 UUID；null 回傳 empty 且不發查詢
     * @return series 主鍵；查無為 empty
     */
    public Optional<Long> resolveSeriesIdByUuid(UUID seriesUuid) {
        return identityResolver.resolveSeriesIdByUuid(seriesUuid);
    }
}
