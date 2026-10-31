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
 *   <li>SeriesService: status / authorUuid / seriesUuid / uuid</li>
 *   <li>SeriesFacadeImpl: seriesUuid / seriesPosition</li>
 * </ul>
 *
 * @param uuid            文章公開 UUID
 * @param status          文章狀態（PUBLISHED / DRAFT / PENDING_REVIEW / etc.）— 用 String 避免跨模組 import enum
 * @param seriesPosition  在 series 內位置（nullable）
 * @param authorUuid      作者公開 UUID（直接取自 {@code articles.author_uuid}）
 * @param seriesUuid      所屬 series 公開 UUID（直接取自 {@code articles.series_uuid}）。
 *                        無系列時為 null；<b>系列剛被刪除、刪除事件尚未清空歸屬時仍會帶出已不存在的系列 UUID</b>
 *                        （最終一致，事件或每日校正會清空）
 *
 * @author Yuan
 * @version 4.0（移除 Long 主鍵 id 與舊 4 參數建構子）
 */
public record ArticleData(
    UUID uuid,
    String status,
    Integer seriesPosition,
    UUID authorUuid,
    UUID seriesUuid
) {}
