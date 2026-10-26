package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.UUID;

/**
 * 跨模組 article content 完整內容 DTO。
 *
 * <p>比 ArticleData 多含 title / slug / content / summary / coverImageUrl 5 個欄位，
 * 適用 VersioningService.snapshotFromContent / AutoSnapshotPolicy.shouldSnapshot 等
 * 需要「完整 article 內容快照」的場景。</p>
 *
 * <p>由 ArticleFacadeImpl 從 Article entity 轉換。</p>
 *
 * @param id              文章資料庫主鍵
 * @param uuid            文章公開 UUID
 * @param title           文章標題
 * @param slug            URL slug
 * @param content         markdown 原文
 * @param summary         摘要
 * @param coverImageUrl   封面圖 URL（nullable）
 * @param status          文章狀態（PUBLISHED / DRAFT / PENDING_REVIEW / etc.）— String 對齊 ArticleData 避免 cross-module enum import
 * @param authorUuid      作者公開 UUID（直接取自 {@code articles.author_uuid}，M6-a 起 Long 的作者主鍵已移除）。
 *                        例外：舊 8 參數建構子（僅供過渡期既有呼叫端 / 測試 mock，Z1 移除）產生者恆為 null
 *
 * @author Yuan
 * @version 2.0（M6-a：移除 authorId）
 */
public record ArticleContentData(
    Long id,
    UUID uuid,
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status,
    UUID authorUuid
) {

    /**
     * 舊 8 參數建構子，<b>僅供過渡期既有呼叫端使用，Z1 移除</b>。
     *
     * <p>委派到 canonical 建構子，{@code authorUuid} 為 null。</p>
     *
     * @param id            文章資料庫主鍵
     * @param uuid          文章公開 UUID
     * @param title         文章標題
     * @param slug          URL slug
     * @param content       markdown 原文
     * @param summary       摘要
     * @param coverImageUrl 封面圖 URL（nullable）
     * @param status        文章狀態
     */
    public ArticleContentData(Long id, UUID uuid, String title, String slug,
                              String content, String summary, String coverImageUrl, String status) {
        this(id, uuid, title, slug, content, summary, coverImageUrl, status, null);
    }
}
