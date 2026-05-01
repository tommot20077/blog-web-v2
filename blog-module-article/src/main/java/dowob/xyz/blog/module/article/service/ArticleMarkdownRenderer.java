package dowob.xyz.blog.module.article.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.TextCollectingVisitor;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;

/**
 * 文章 Markdown 渲染器。
 *
 * <p>支援 CommonMark 完整語法（heading / image / code block / table / list 等），
 * 渲染後再以 OWASP HtmlSanitizer 白名單剝除 dangerous 元素。</p>
 *
 * <h3>白名單範圍</h3>
 * <ul>
 *   <li>結構：p、br、hr、blockquote、ul、ol、li</li>
 *   <li>標題：h1-h6（h1 通常給文章標題，markdown 內習慣 h2 起；不過全部允許）</li>
 *   <li>強調：strong、em、code、del、s</li>
 *   <li>程式碼區塊：pre、code（含 class for shiki/highlight）</li>
 *   <li>連結：a（href 限 http/https；強制 nofollow noopener、target=_blank）</li>
 *   <li>圖片：img（src 限 http/https；alt、title 屬性允許）</li>
 *   <li>表格：table、thead、tbody、tr、th、td</li>
 * </ul>
 *
 * <p>禁用：script、iframe、object、embed、form、input、style、link 等危險元素，
 * 以及任何 on* 事件屬性與 javascript: URL。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class ArticleMarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory sanitizer;

    public ArticleMarkdownRenderer() {
        MutableDataSet options = new MutableDataSet();
        // 讓 flexmark 輸出 raw HTML，由 OWASP 後續消毒
        options.set(HtmlRenderer.ESCAPE_HTML, false);
        options.set(HtmlRenderer.SUPPRESS_HTML, false);

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();

        this.sanitizer = new HtmlPolicyBuilder()
                // 結構
                .allowElements("p", "br", "hr", "blockquote", "ul", "ol", "li")
                // 標題
                .allowElements("h1", "h2", "h3", "h4", "h5", "h6")
                // 強調
                .allowElements("strong", "em", "code", "del", "s")
                // 程式碼區塊
                .allowElements("pre", "code")
                .allowAttributes("class").onElements("code", "pre")
                // 連結
                .allowElements("a")
                .allowUrlProtocols("http", "https")
                .allowAttributes("href").onElements("a")
                .requireRelNofollowOnLinks()
                .requireRelsOnLinks("noopener")
                // 圖片
                .allowElements("img")
                .allowUrlProtocols("http", "https")
                .allowAttributes("src", "alt", "title").onElements("img")
                // 表格
                .allowElements("table", "thead", "tbody", "tr", "th", "td")
                .allowAttributes("colspan", "rowspan").onElements("th", "td")
                .toFactory();
    }

    /**
     * Markdown → 安全 HTML。
     *
     * @param markdown 原始 Markdown，可為 null
     * @return 渲染並 sanitize 後的 HTML；null 輸入回傳 null，空字串輸入回傳空字串
     */
    public String render(String markdown) {
        if (markdown == null) {
            return null;
        }
        if (markdown.isEmpty()) {
            return "";
        }
        Node doc = parser.parse(markdown);
        String html = renderer.render(doc);
        String sanitized = sanitizer.sanitize(html);
        // OWASP policy 對 target 屬性比較嚴格，後處理只對「沒有 target」的 <a> 補上 target="_blank"
        // negative lookahead 避免重複注入造成 target="_blank" target="_blank" 之類無效 HTML
        sanitized = sanitized.replaceAll("(?i)(<a\\b(?![^>]*\\btarget\\s*=)[^>]*?)>", "$1 target=\"_blank\">");
        return sanitized;
    }

    /**
     * Markdown → 純文字（用於摘要提取）。不需 sanitize（純文字無 HTML 風險）。
     *
     * @param markdown 原始 Markdown，可為 null
     * @return 純文字；null 輸入回傳 null
     */
    public String toPlainText(String markdown) {
        if (markdown == null) {
            return null;
        }
        Node doc = parser.parse(markdown);
        TextCollectingVisitor visitor = new TextCollectingVisitor();
        return visitor.collectAndGetText(doc);
    }
}
