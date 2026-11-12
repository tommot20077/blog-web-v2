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
 * 對齊 ReadingFacade pattern：interface 在 infrastructure，impl 在 owning module。
 * 跨模組識別一律是 series 公開 UUID，不暴露資料庫主鍵。</p>
 *
 * @author Yuan
 * @version 2.0（移除 Long 主鍵 API）
 */
public interface SeriesFacade {

    /**
     * 取得文章在 series 中的導覽（prev/next）。
     *
     * <p>文章不存在、不在任何 series，或所屬 series 已不存在時皆回傳 {@link Optional#empty()}。</p>
     *
     * @param articleUuid 文章公開 UUID
     * @return 若文章在 series 中回傳導覽；否則 Optional.empty()
     */
    Optional<SeriesNavigation> getSeriesNavigation(UUID articleUuid);

    /**
     * 以 series 公開 UUID 批次取得基本資訊。
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
}
