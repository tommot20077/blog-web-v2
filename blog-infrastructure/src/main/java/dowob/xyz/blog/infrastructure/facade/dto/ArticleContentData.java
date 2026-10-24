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
 * @param authorId        作者資料庫主鍵
 * @param title           文章標題
 * @param slug            URL slug
 * @param content         markdown 原文
 * @param summary         摘要
 * @param coverImageUrl   封面圖 URL（nullable）
 * @param status          文章狀態（PUBLISHED / DRAFT / PENDING_REVIEW / etc.）— String 對齊 ArticleData 避免 cross-module enum import
 * @param authorUuid      作者公開 UUID（設計 §3.1 過渡欄位）。<b>正常情況必定非 null；為 null 代表資料異常</b>
 *                        （articles.author_id 有 FK 指向 users 且使用者只軟刪除，owner 模組查不到時會記 warn）。
 *                        例外：舊 9 參數建構子（僅供過渡期既有呼叫端 / 測試 mock，Z1 移除）產生者恆為 null
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleContentData(
    Long id,
    UUID uuid,
    Long authorId,
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status,
    UUID authorUuid
) {

    /**
     * 舊 9 參數建構子，<b>僅供過渡期既有呼叫端使用，Z1 移除</b>。
     *
     * <p>委派到 canonical 建構子，{@code authorUuid} 為 null。</p>
     *
     * @param id            文章資料庫主鍵
     * @param uuid          文章公開 UUID
     * @param authorId      作者資料庫主鍵
     * @param title         文章標題
     * @param slug          URL slug
     * @param content       markdown 原文
     * @param summary       摘要
     * @param coverImageUrl 封面圖 URL（nullable）
     * @param status        文章狀態
     */
    public ArticleContentData(Long id, UUID uuid, Long authorId, String title, String slug,
                              String content, String summary, String coverImageUrl, String status) {
        this(id, uuid, authorId, title, slug, content, summary, coverImageUrl, status, null);
    }
}
