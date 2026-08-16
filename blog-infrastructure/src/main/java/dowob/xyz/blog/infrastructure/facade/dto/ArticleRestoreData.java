package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.List;
import java.util.UUID;

/**
 * 跨模組 article restore mutation DTO（給 VersioningService.restore 用）。
 *
 * <p>包含 caller (VersioningService) 從 ArticleVersion 取出 + markdown 渲染後
 * 要寫回 article 的全部欄位。tags 一併傳，由 ArticleFacade.applyRestoreContent
 * 內部呼叫 syncArticleTags 處理（必須在 publish events 之前）。</p>
 *
 * @param title          文章標題
 * @param slug           URL slug
 * @param content        markdown 原文
 * @param summary        摘要
 * @param coverImageUrl  封面圖 URL（nullable）
 * @param status         文章狀態（對齊 ArticleStatus.name() — 例：PUBLISHED / DRAFT；nullable 表示不更新狀態）
 * @param contentHtml    markdownRenderer.render(content) 由 caller 算好（避免 facade 跨模組依賴 markdown renderer）
 * @param toc            章節導覽 JSON 字串，與 contentHtml 出自同一次 render 由 caller 算好。
 *                       必須一併傳：只回填 contentHtml 會讓 articles.toc 停在還原前那版，
 *                       API 回的章節導覽將指向 HTML 中不存在的錨點（null 視為空陣列）
 * @param tags           標籤 UUID 列表（null 視為空清單）
 *
 * @author Yuan
 * @version 1.0
 */
public record ArticleRestoreData(
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String status,
    String contentHtml,
    String toc,
    List<UUID> tags
) {}
