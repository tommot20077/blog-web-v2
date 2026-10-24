package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Series 跨模組 read facade。
 *
 * <p>給 ArticleQueryService 等跨模組讀取 series 導覽資訊用。
 * 對齊 ReadingFacade pattern：interface 在 infrastructure，impl 在 owning module。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface SeriesFacade {

    /**
     * 取得文章在 series 中的導覽（prev/next）。
     *
     * @param articleId 文章資料庫主鍵
     * @return 若文章在 series 中回傳導覽；否則 Optional.empty()
     */
    Optional<SeriesNavigation> getSeriesNavigation(Long articleId);

    /**
     * 批次取得多個 series 的基本資訊。
     *
     * @param seriesIds series 主鍵集合；空集合回傳空 Map
     * @return series 主鍵 → 基本資訊
     */
    Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(Collection<Long> seriesIds);

    /**
     * 以 series 公開 UUID 查詢其資料庫主鍵（設計 §3.1 過渡 API）。
     *
     * <p>供 M4～M6 過渡期，article 模組以 series uuid 查詢文章時，先換成 series 主鍵
     * （此時 {@code articles} 仍只有 {@code series_id}）。Z1 收尾階段隨 Long 欄位一併移除。</p>
     *
     * @param seriesUuid series 公開 UUID；null 視為查無
     * @return series 主鍵；不存在回傳 {@link Optional#empty()}
     */
    Optional<Long> findIdByUuid(UUID seriesUuid);

    /**
     * 批次把 series 主鍵轉成公開 UUID（設計 §3.1 過渡 API）。
     *
     * <p>供 article 模組填 {@code ArticleData.seriesUuid} 時一次轉換整批，<b>不得</b>逐筆呼叫、
     * 也不得改為 JOIN series 表（跨模組邊界）。輸入可含重複與 {@code null}，實作負責去重並切批。</p>
     *
     * @param seriesIds series 主鍵集合；null 或空集合回傳空 Map
     * @return series 主鍵 → 公開 UUID；查無的主鍵不出現在結果中，永不回傳 null
     */
    Map<Long, UUID> findUuidsByIds(Collection<Long> seriesIds);
}
