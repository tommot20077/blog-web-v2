package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.UUID;

/**
 * 跨模組 article 資料 DTO（給 ArticleFacade 跨模組讀取用）。
 *
 * <p>Infrastructure 模組不依賴 article 模組（會破壞模組依賴方向），
 * 因此不能直接回傳 Article entity。本 record 含跨模組常用欄位，
 * 由 ArticleFacadeImpl 從 Article entity 轉換。</p>
 *
 * <p>欄位選擇對應 audit 報告中跨模組實際用法：</p>
 * <ul>
 *   <li>SeriesService: status / authorUuid / seriesUuid / id / uuid</li>
 *   <li>SeriesFacadeImpl: seriesUuid / seriesPosition</li>
 *   <li>ReadingProgressService: id → uuid mapping</li>
 * </ul>
 *
 * @param id              文章資料庫主鍵
 * @param uuid            文章公開 UUID
 * @param status          文章狀態（PUBLISHED / DRAFT / PENDING_REVIEW / etc.）— 用 String 避免跨模組 import enum
 * @param seriesPosition  在 series 內位置（nullable）
 * @param authorUuid      作者公開 UUID（直接取自 {@code articles.author_uuid}，M6-a 起 Long 的作者主鍵已移除）。
 *                        例外：舊 4 參數建構子（僅供過渡期既有呼叫端 / 測試 mock，Z1 移除）產生者恆為 null
 * @param seriesUuid      所屬 series 公開 UUID（直接取自 {@code articles.series_uuid}，M6-b 起 Long 的 series 主鍵已移除）。
 *                        無系列時為 null；<b>系列剛被刪除、刪除事件尚未清空歸屬時仍會帶出已不存在的系列 UUID</b>
 *                        （最終一致，事件或每日校正會清空）。舊 4 參數建構子產生者恆為 null
 *
 * @author Yuan
 * @version 3.0（M6-b：移除 seriesId）
 */
public record ArticleData(
    Long id,
    UUID uuid,
    String status,
    Integer seriesPosition,
    UUID authorUuid,
    UUID seriesUuid
) {

    /**
     * 舊 4 參數建構子，<b>僅供過渡期既有呼叫端使用，Z1 移除</b>。
     *
     * <p>委派到 canonical 建構子，{@code authorUuid} 與 {@code seriesUuid} 為 null。
     * article 模組內產生 ArticleData 的地方一律改用 canonical 建構子並填值；
     * 本建構子只為了不破壞他模組既有的建構呼叫與測試 mock。</p>
     *
     * @param id             文章資料庫主鍵
     * @param uuid           文章公開 UUID
     * @param status         文章狀態
     * @param seriesPosition 在 series 內位置（nullable）
     */
    public ArticleData(Long id, UUID uuid, String status, Integer seriesPosition) {
        this(id, uuid, status, seriesPosition, null, null);
    }
}
