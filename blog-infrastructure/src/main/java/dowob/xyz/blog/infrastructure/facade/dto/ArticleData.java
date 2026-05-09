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
    Integer seriesPosition
) {}
