package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.List;
import java.util.UUID;

/**
 * 跨模組 article restore 的「原始快照內容」DTO（給 {@code ArticleFacade.applyRestoreContent(UUID, ArticleRestoreSource)} 用）。
 *
 * <p>本 DTO <b>不含</b> {@code contentHtml} 與 {@code toc}。
 * Markdown → HTML＋TOC 的渲染是 article 模組的職責，呼叫端（version 模組）只需要把快照的 Markdown
 * 原文交出來，由 article 在自己的邊界內以同一次 render 同時算出 HTML 與 TOC，
 * 使 version 模組不必（也不應）import article 的渲染器。</p>
 *
 * <p><strong>SEC-02：本 DTO 刻意不含 status</strong>。還原只還原內容，文章狀態一律不動；
 * 讓還原路徑在型別上就無法傳遞 status，避免日後有人順手接回而重開繞過審核的破口。</p>
 *
 * @param title         文章標題
 * @param slug          URL slug
 * @param content       markdown 原文（article 會據此渲染 HTML 與 TOC）
 * @param summary       摘要
 * @param coverImageUrl 封面圖 URL（nullable）
 * @param tags          標籤 UUID 列表（null 視為空清單）
 *
 * @author Yuan
 * @version 1.1（ArticleRestoreData 隨 Long 版 applyRestoreContent 移除）
 */
public record ArticleRestoreSource(
    String title,
    String slug,
    String content,
    String summary,
    String coverImageUrl,
    List<UUID> tags
) {}
