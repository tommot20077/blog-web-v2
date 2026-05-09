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
    String status
) {}
