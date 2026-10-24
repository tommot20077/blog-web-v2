package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.model.Article;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 將 {@link Article} 實體組裝為跨模組 {@link ArticleData} 的元件（設計 §3.1「過渡 API」）。
 *
 * <p>集中處理過渡期的兩個 UUID 欄位，讓 {@code ArticleFacadeImpl} 與 {@code ArticleLookupFacadeImpl}
 * 不必各寫一份，也保證<b>批次轉換一次取得、不逐筆查詢</b>：</p>
 * <ul>
 *   <li>{@code authorUuid}：article 模組內 {@code users} 仍是 reference data，經
 *       {@link ArticleMapper#findUserUuidsByIds} 一次取得整批作者 uuid（M6 改為 {@code author_uuid} 欄位後移除）；</li>
 *   <li>{@code seriesUuid}：經 {@link SeriesFacade#findUuidsByIds} 批次轉換，<b>不得</b> JOIN series 表
 *       （跨模組邊界，CrossModuleBoundaryTest 會擋）。</li>
 * </ul>
 *
 * <p><b>bean 循環</b>：{@code SeriesFacadeImpl} 已建構子注入 {@code ArticleFacade}，若在此直接注入
 * {@link SeriesFacade} 會形成循環，故一律以 {@link ObjectProvider} 延遲取得，且只在<b>確實有 series id 要轉換時</b>
 * 才取用。不得改為 field injection 或開啟 {@code allow-circular-references}。</p>
 *
 * <p><b>依賴限制</b>：本類別只依賴 mapper 與 {@link ObjectProvider}，不依賴任何 service / facade，
 * 因此可被 {@code ArticleLookupFacadeImpl} 這個「最小依賴」bean 安全使用（不會重新引入 article ⇄ file 循環）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class ArticleDataAssembler {

    /** 文章 MyBatis Mapper（讀 users reference data 取作者 uuid） */
    private final ArticleMapper articleMapper;

    /** 延遲取得 SeriesFacade，打斷 SeriesFacadeImpl → ArticleFacade 的 bean 循環 */
    private final ObjectProvider<SeriesFacade> seriesFacadeProvider;

    /**
     * 建構子。
     *
     * @param articleMapper        文章 MyBatis Mapper
     * @param seriesFacadeProvider SeriesFacade 的延遲提供者
     */
    public ArticleDataAssembler(ArticleMapper articleMapper, ObjectProvider<SeriesFacade> seriesFacadeProvider) {
        this.articleMapper = articleMapper;
        this.seriesFacadeProvider = seriesFacadeProvider;
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
        Map<Long, UUID> authorUuids = resolveAuthorUuids(
                articles.stream().map(Article::getAuthorId).toList());
        Map<Long, UUID> seriesUuids = resolveSeriesUuids(
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
     * 批次把使用者主鍵轉成公開 UUID。
     *
     * <p>輸入去重並忽略 null，經 {@link BatchedQuery} 切批；查無的使用者不出現在結果中。</p>
     *
     * @param userIds 使用者主鍵集合；null 或空集合回傳空 Map 且不發查詢
     * @return 使用者主鍵 → 公開 UUID
     */
    public Map<Long, UUID> resolveAuthorUuids(Collection<Long> userIds) {
        Set<Long> distinct = distinctNonNull(userIds);
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        return BatchedQuery.queryInBatches(distinct, articleMapper::findUserUuidsByIds).stream()
                .collect(Collectors.toMap(
                        ArticleMapper.UserUuidRow::userId,
                        ArticleMapper.UserUuidRow::userUuid,
                        (first, second) -> first));
    }

    /**
     * 批次把 series 主鍵轉成公開 UUID（經 {@link SeriesFacade}，延遲取得）。
     *
     * @param seriesIds series 主鍵集合（可含 null 與重複）
     * @return series 主鍵 → 公開 UUID；集合中沒有任何 series id 時不取用 SeriesFacade 並回傳空 Map
     */
    private Map<Long, UUID> resolveSeriesUuids(Collection<Long> seriesIds) {
        Set<Long> distinct = distinctNonNull(seriesIds);
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        return seriesFacadeProvider.getObject().findUuidsByIds(distinct);
    }

    /**
     * 去重並忽略 null，保留首次出現順序。
     *
     * @param ids 原始 id 集合（可為 null）
     * @return 去重後的 id 集合
     */
    private static Set<Long> distinctNonNull(Collection<Long> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
