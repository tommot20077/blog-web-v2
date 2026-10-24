package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.service.ArticleIdentityResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 將 {@link Article} 實體組裝為跨模組 {@link ArticleData} 的元件（設計 §3.1「過渡 API」）。
 *
 * <p>集中處理過渡期的兩個 UUID 欄位，讓 {@code ArticleFacadeImpl} 與 {@code ArticleLookupFacadeImpl}
 * 不必各寫一份，也保證<b>批次轉換一次取得、不逐筆查詢</b>。轉換本身委派給
 * {@link ArticleIdentityResolver}（與 {@code ArticleEventPublisher} 共用；SeriesFacade 的 ObjectProvider
 * 延遲取得與 bean 循環說明見該類別）。</p>
 *
 * <p><b>依賴限制</b>：本類別只依賴 {@link ArticleIdentityResolver}（其本身只依賴 mapper 與 ObjectProvider），
 * 因此可被 {@code ArticleLookupFacadeImpl} 這個「最小依賴」bean 安全使用（不會重新引入 article ⇄ file 循環）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ArticleDataAssembler {

    /** 作者／series uuid 的批次轉換元件 */
    private final ArticleIdentityResolver identityResolver;

    /**
     * 建構子。
     *
     * @param identityResolver 作者／series uuid 的批次轉換元件
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
     * <p>作者與系列各<b>只查一次</b>（去重、忽略 null 後整批送出），保留輸入順序。
     * 無系列的文章 {@code seriesUuid} 為 null；系列在 SeriesFacade 查無時同樣為 null。</p>
     *
     * @param articles 文章實體集合；null 或空集合回傳空清單且不發任何查詢
     * @return 與輸入同順序的 ArticleData 清單
     */
    public List<ArticleData> toArticleDataList(Collection<Article> articles) {
        if (articles == null || articles.isEmpty()) {
            return List.of();
        }
        Map<Long, UUID> authorUuids = identityResolver.resolveAuthorUuids(
                articles.stream().map(Article::getAuthorId).toList());
        Map<Long, UUID> seriesUuids = identityResolver.resolveSeriesUuids(
                articles.stream().map(Article::getSeriesId).toList());
        articles.forEach(a -> warnIfAuthorUuidMissing(a.getId(), a.getAuthorId(), authorUuids));
        return articles.stream()
                .map(a -> new ArticleData(
                        a.getId(),
                        a.getUuid(),
                        a.getAuthorId(),
                        a.getStatus() != null ? a.getStatus().name() : null,
                        a.getSeriesId(),
                        a.getSeriesPosition(),
                        a.getAuthorId() == null ? null : authorUuids.get(a.getAuthorId()),
                        a.getSeriesId() == null ? null : seriesUuids.get(a.getSeriesId())))
                .toList();
    }

    /**
     * 作者 uuid 查不到時記錄 warn（值仍為 null）。
     *
     * <p>{@code articles.author_id} 有 FK 指向 {@code users}，且使用者只做軟刪除（列仍在），
     * 因此正常情況下作者 uuid 必定查得到；查不到代表資料異常，需要有人注意到而非靜默給 null。</p>
     *
     * @param articleId   文章主鍵（僅供日誌）
     * @param authorId    作者主鍵；null 時不檢查
     * @param authorUuids 已解析的作者主鍵 → UUID
     */
    static void warnIfAuthorUuidMissing(Long articleId, Long authorId, Map<Long, UUID> authorUuids) {
        if (authorId != null && authorUuids.get(authorId) == null) {
            log.warn("查無文章作者的 uuid（資料異常：articles.author_id 應有 FK 指向 users）：articleId={}, authorId={}",
                    articleId, authorId);
        }
    }

    /**
     * 批次把使用者主鍵轉成公開 UUID（委派給 {@link ArticleIdentityResolver}，供 facade 取作者 uuid）。
     *
     * @param userIds 使用者主鍵集合；null 或空集合回傳空 Map 且不發查詢
     * @return 使用者主鍵 → 公開 UUID
     */
    public Map<Long, UUID> resolveAuthorUuids(Collection<Long> userIds) {
        return identityResolver.resolveAuthorUuids(userIds);
    }
}
