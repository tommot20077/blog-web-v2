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
 *   <li>SeriesService: status / authorId / seriesId / id / uuid</li>
 *   <li>SeriesFacadeImpl: seriesId / seriesPosition</li>
 *   <li>ReadingProgressService: id → uuid mapping</li>
 * </ul>
 *
 * @param id              文章資料庫主鍵
 * @param uuid            文章公開 UUID
 * @param authorId        作者資料庫主鍵
 * @param status          文章狀態（PUBLISHED / DRAFT / PENDING_REVIEW / etc.）— 用 String 避免跨模組 import enum
 * @param seriesId        所屬 series 主鍵（nullable）
 * @param seriesPosition  在 series 內位置（nullable）
 * @param authorUuid      作者公開 UUID（設計 §3.1 過渡欄位）。<b>正常情況必定非 null；為 null 代表資料異常</b>
 *                        （articles.author_id 有 FK 指向 users 且使用者只軟刪除，owner 模組查不到時會記 warn）。
 *                        例外：舊 6 參數建構子（僅供過渡期既有呼叫端 / 測試 mock，Z1 移除）產生者恆為 null
 * @param seriesUuid      所屬 series 公開 UUID（設計 §3.1 過渡欄位）。<b>無系列或系列已不存在時為 null</b>；舊 6 參數建構子產生者恆為 null
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleData(
    Long id,
    UUID uuid,
    Long authorId,
    String status,
    Long seriesId,
    Integer seriesPosition,
    UUID authorUuid,
    UUID seriesUuid
) {

    /**
     * 舊 6 參數建構子，<b>僅供過渡期既有呼叫端使用，Z1 移除</b>。
     *
     * <p>委派到 canonical 建構子，{@code authorUuid} 與 {@code seriesUuid} 為 null。
     * article 模組內產生 ArticleData 的地方一律改用 canonical 建構子並填值；
     * 本建構子只為了不破壞他模組既有的建構呼叫與測試 mock。</p>
     *
     * @param id             文章資料庫主鍵
     * @param uuid           文章公開 UUID
     * @param authorId       作者資料庫主鍵
     * @param status         文章狀態
     * @param seriesId       所屬 series 主鍵（nullable）
     * @param seriesPosition 在 series 內位置（nullable）
     */
    public ArticleData(Long id, UUID uuid, Long authorId, String status, Long seriesId, Integer seriesPosition) {
        this(id, uuid, authorId, status, seriesId, seriesPosition, null, null);
    }
}
