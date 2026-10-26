package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.SeriesBasicInfo;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Series 跨模組 read facade。
 *
 * <p>給 ArticleQueryService 等跨模組讀取 series 導覽資訊用。
 * 對齊 ReadingFacade pattern：interface 在 infrastructure，impl 在 owning module。</p>
 *
 * <p><b>過渡 API</b>：{@link #batchGetSeriesBasicInfo}、{@link #findIdByUuid}、{@link #findUuidsByIds}、
 * {@link #findIdsByUuids} 仍以 series 主鍵（Long）往來。M6-b 起 article 的 {@code articles.series_uuid}
 * 直接儲存公開 UUID，article 已<b>不再呼叫</b>這四個方法（改用 {@link #batchGetSeriesBasicInfoByUuids}）；
 * 它們留待 Z1 收尾階段移除。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface SeriesFacade {

    /**
     * 取得文章在 series 中的導覽（prev/next）。
     *
     * <p>M4-b 起以文章公開 UUID 識別（series 是此 facade 的 owner，直接改簽章，不保留 Long 版）。
     * 文章不存在、不在任何 series，或所屬 series 已不存在時皆回傳 {@link Optional#empty()}。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 若文章在 series 中回傳導覽；否則 Optional.empty()
     */
    Optional<SeriesNavigation> getSeriesNavigation(UUID articleUuid);

    /**
     * 批次取得多個 series 的基本資訊。
     *
     * @param seriesIds series 主鍵集合；空集合回傳空 Map
     * @return series 主鍵 → 基本資訊
     */
    Map<Long, SeriesBasicInfo> batchGetSeriesBasicInfo(Collection<Long> seriesIds);

    /**
     * 以 series 公開 UUID 批次取得基本資訊（M6-b 新增，取代 Long 版 {@link #batchGetSeriesBasicInfo} 的呼叫端）。
     *
     * <p>供 article 模組列表 enrich 時補 {@code seriesUuid} / {@code seriesTitle}：article 已直接儲存
     * {@code series_uuid}，不再需要主鍵。實作端自行去重、忽略 null，並經 {@code BatchedQuery} 切批；
     * 查無的 series（已被刪除、事件尚未清空歸屬）不出現在結果中。</p>
     *
     * @param seriesUuids series 公開 UUID 集合；null 或空集合回傳空 Map（不查 DB）；null 元素會被忽略
     * @return series 公開 UUID → 基本資訊；永不回傳 null
     */
    Map<UUID, SeriesBasicInfo> batchGetSeriesBasicInfoByUuids(Collection<UUID> seriesUuids);

    /**
     * 篩出 uuids 中「仍存在」的系列 UUID。
     *
     * <p>系列沒有狀態欄位，存在即回傳；被刪除（查無此列）者不會出現在結果中。
     * 供其他模組的每日孤兒校正排程判斷「所屬系列是否已刪除」。</p>
     *
     * <p>實作端會自行去重並經 {@code BatchedQuery} 切批，caller 可一次傳入任意數量。</p>
     *
     * @param uuids 待查證的系列公開 UUID；null 或空集合回傳空集合（不查 DB）；null 元素會被忽略
     * @return 其中仍存在於 series 表的 UUID 集合
     */
    Set<UUID> filterExistingUuids(Collection<UUID> uuids);

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

    /**
     * 批次把 series 公開 UUID 轉成資料庫主鍵（M4-a 過渡 API，{@link #findUuidsByIds} 的反向）。
     *
     * <p>供 article 模組的 UUID 版 series 查詢（例如 {@code countPublishedBySeriesUuids}）一次轉換整批，
     * <b>不得</b>逐筆呼叫 {@link #findIdByUuid}，也不得改為 JOIN series 表（跨模組邊界）。
     * 實作負責去重、忽略 null 並經 {@code BatchedQuery} 切批。
     * 過渡期 API：M6-b 已將 {@code articles.series_id} 改為 {@code series_uuid}，article 不再呼叫；Z1 收尾階段移除。</p>
     *
     * @param seriesUuids series 公開 UUID 集合；null 或空集合回傳空 Map（不查 DB）；null 元素會被忽略
     * @return series 公開 UUID → 主鍵；查無的 UUID 不出現在結果中，永不回傳 null
     */
    Map<UUID, Long> findIdsByUuids(Collection<UUID> seriesUuids);
}
