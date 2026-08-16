package dowob.xyz.blog.module.article.model.dto.response;

/**
 * 文章章節導覽（Table of Contents）單一條目。
 *
 * <p>對應文章內文中的一個 h2 或 h3 標題。此結構同時作為：</p>
 * <ul>
 *   <li>渲染器 {@code ArticleMarkdownRenderer} 走訪 Markdown AST 後的輸出項目；</li>
 *   <li>持久化至 {@code articles.toc} 欄位的 JSON 陣列元素；</li>
 *   <li>對外 API 回應中 {@code toc} 陣列的元素（結構化，非原始 JSON 字串）。</li>
 * </ul>
 *
 * <p>{@code id} 恆為 {@code heading-} 前綴加上 Unicode slug，且必須匹配
 * {@code ^heading-[\p{L}\p{N}-]{1,64}$}，此約束為安全機制——使 sanitizer 得以放行
 * 渲染器產生的 id、剝除使用者於 Markdown 內嵌 raw HTML 所注入的任意 id。</p>
 *
 * @param id    對應標題於 HTML 中的錨點 id（含 {@code heading-} 前綴）
 * @param text  標題的純文字內容（保留原始大小寫，供側欄顯示）
 * @param level 標題層級，僅可能為 2（h2）或 3（h3）
 * @author Yuan
 * @version 1.0
 */
public record TocEntry(String id, String text, int level) {
}
