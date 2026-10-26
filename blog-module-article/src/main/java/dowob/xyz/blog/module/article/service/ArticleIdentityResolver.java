package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * series 的「主鍵 ↔ 公開 UUID」批次轉換元件（設計 §3.1「過渡 API」；M6-b 以 {@code series_uuid} 欄位取代後移除）。
 *
 * <p>由 {@code ArticleDataAssembler}（填 {@code ArticleData} 的 uuid 欄位）與
 * {@link ArticleEventPublisher}（填事件的 uuid 欄位）共用，讓兩處走同一套機制：
 * series uuid 經 {@link SeriesFacade#findUuidsByIds} 批次轉換，<b>不得</b> JOIN series 表
 * （跨模組邊界，CrossModuleBoundaryTest 會擋）。</p>
 *
 * <p>作者 uuid 自 M6-a 起直接存於 {@code articles.author_uuid}，不再經本元件轉換。</p>
 *
 * <p><b>bean 循環</b>：{@code SeriesFacadeImpl} 已建構子注入 {@code ArticleFacade}，若在此直接注入
 * {@link SeriesFacade} 會形成循環，故一律以 {@link ObjectProvider} 延遲取得，且只在<b>確實有 series id 要轉換時</b>
 * 才取用。不得改為 field injection 或開啟 {@code allow-circular-references}。</p>
 *
 * <p><b>依賴限制</b>：只依賴 {@link ObjectProvider}，不依賴任何 service／facade／mapper，
 * 放在 service 套件是為了讓 facade 與 service 都能單向依賴它而不形成套件循環。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Component
public class ArticleIdentityResolver {

    /** 延遲取得 SeriesFacade，打斷 SeriesFacadeImpl → ArticleFacade 的 bean 循環 */
    private final ObjectProvider<SeriesFacade> seriesFacadeProvider;

    /**
     * 建構子。
     *
     * @param seriesFacadeProvider SeriesFacade 的延遲提供者
     */
    public ArticleIdentityResolver(ObjectProvider<SeriesFacade> seriesFacadeProvider) {
        this.seriesFacadeProvider = seriesFacadeProvider;
    }

    /**
     * 批次把 series 主鍵轉成公開 UUID（經 {@link SeriesFacade}，延遲取得）。
     *
     * @param seriesIds series 主鍵集合（可含 null 與重複）
     * @return series 主鍵 → 公開 UUID；集合中沒有任何 series id 時不取用 SeriesFacade 並回傳空 Map
     */
    public Map<Long, UUID> resolveSeriesUuids(Collection<Long> seriesIds) {
        Set<Long> distinct = distinctNonNull(seriesIds);
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        return seriesFacadeProvider.getObject().findUuidsByIds(distinct);
    }

    /**
     * 批次把 series 公開 UUID 轉成資料庫主鍵（M4-a 過渡 API；經 {@link SeriesFacade#findIdsByUuids}，延遲取得）。
     *
     * <p>輸入去重並忽略 null 後<b>整批一次</b>送出，不逐筆呼叫 {@code findIdByUuid}；
     * 查無的 UUID 不出現在結果中。M6 以 {@code articles.series_uuid} 直查後移除。</p>
     *
     * @param seriesUuids series 公開 UUID 集合（可含 null 與重複）
     * @return series 公開 UUID → 主鍵；集合中沒有任何 UUID 時不取用 SeriesFacade 並回傳空 Map
     */
    public Map<UUID, Long> resolveSeriesIdsByUuids(Collection<UUID> seriesUuids) {
        if (seriesUuids == null) {
            return Collections.emptyMap();
        }
        Set<UUID> distinct = seriesUuids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        return seriesFacadeProvider.getObject().findIdsByUuids(distinct);
    }

    /**
     * 把單一 series 公開 UUID 轉成資料庫主鍵（M4-a 過渡 API；經 {@link SeriesFacade#findIdByUuid}，延遲取得）。
     *
     * <p>僅供「單一 series」的查詢使用（單篇導覽、單一 series 的文章清單）；多筆請用
     * {@link #resolveSeriesIdsByUuids}，不得在迴圈中呼叫本方法。</p>
     *
     * @param seriesUuid series 公開 UUID；null 時不取用 SeriesFacade 並回傳 empty
     * @return series 主鍵；查無時為 {@link Optional#empty()}
     */
    public Optional<Long> resolveSeriesIdByUuid(UUID seriesUuid) {
        if (seriesUuid == null) {
            return Optional.empty();
        }
        return seriesFacadeProvider.getObject().findIdByUuid(seriesUuid);
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
