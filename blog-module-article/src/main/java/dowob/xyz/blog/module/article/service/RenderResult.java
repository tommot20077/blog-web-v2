package dowob.xyz.blog.module.article.service;

import dowob.xyz.blog.module.article.model.dto.response.TocEntry;

import java.util.List;

/**
 * Markdown 渲染結果。
 *
 * <p>{@code ArticleMarkdownRenderer#render(String)} 的回傳型別，一次呼叫同時產出
 * 消毒後的 HTML 與依文件順序排列的章節導覽（TOC）。兩者於同一次解析中自同一份 AST
 * 產生，故 TOC 與 HTML 中的 heading id 保證一致。</p>
 *
 * @param html 渲染並經 OWASP HtmlSanitizer 消毒後的安全 HTML
 * @param toc  依文件順序排列的 h2/h3 章節條目；無 heading 時為空清單（非 null）
 * @author Yuan
 * @version 1.0
 */
public record RenderResult(String html, List<TocEntry> toc) {
}
