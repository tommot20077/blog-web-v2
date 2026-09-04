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
 * <p><strong>SEC-02：本 DTO 刻意不含 status</strong>。還原只還原內容，文章狀態一律不動——
 * 快照當時的 status 仍保存在 {@code ArticleVersion} 並由版本詳情 API 回傳（供人判讀「這份快照
 * 是哪個階段的內容」），但永遠不會被套回文章。狀態轉換的唯一真相是
 * {@code ArticleCommandSubService.VALID_TRANSITIONS}；讓還原路徑在型別上就無法傳遞 status，
 * 是為了避免將來有人「順手」把它接回去，重新開出繞過審核的破口。</p>
 *
 * @param title          文章標題
 * @param slug           URL slug
 * @param content        markdown 原文
 * @param summary        摘要
 * @param coverImageUrl  封面圖 URL（nullable）
 * @param contentHtml    markdownRenderer.render(content) 由 caller 算好（避免 facade 跨模組依賴 markdown renderer）
 * @param toc            章節導覽 JSON 字串，與 contentHtml 出自同一次 render 由 caller 算好。
 *                       必須一併傳：只回填 contentHtml 會讓 articles.toc 停在還原前那版，
 *                       API 回的章節導覽將指向 HTML 中不存在的錨點（null 視為空陣列）
 * @param tags           標籤 UUID 列表（null 視為空清單）
 *
 * @author Yuan
 * @version 2.0
 */
public record ArticleRestoreData(
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    String contentHtml,
    String toc,
    List<UUID> tags
) {}
