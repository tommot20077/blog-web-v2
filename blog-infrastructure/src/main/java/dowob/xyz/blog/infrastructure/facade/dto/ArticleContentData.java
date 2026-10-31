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
 * @param uuid            文章公開 UUID
 * @param title           文章標題
 * @param slug            URL slug
 * @param content         markdown 原文
 * @param summary         摘要
 * @param coverImageUrl   封面圖 URL（nullable）
 * @param status          文章狀態（PUBLISHED / DRAFT / PENDING_REVIEW / etc.）— String 對齊 ArticleData 避免 cross-module enum import
 * @param authorUuid      作者公開 UUID（直接取自 {@code articles.author_uuid}）
 *
 * @author Yuan
 * @version 3.0（移除 Long 主鍵 id 與舊 8 參數建構子）
 */
public record ArticleContentData(
    UUID uuid,
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status,
    UUID authorUuid
) {}
