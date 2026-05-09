package dowob.xyz.blog.module.comment.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.parser.ParserEmulationProfile;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.stereotype.Service;

/**
 * 留言 Markdown 渲染器。
 *
 * <p>限制：只允許 inline emphasis、inline code、link、blockquote、line break。
 * 禁用：image、heading、fenced code block、table、list。</p>
 *
 * <h3>安全模型：單層 OWASP 白名單</h3>
 *
 * <p>本實作採用 <strong>單層 OWASP 白名單剝除</strong>，而非「flexmark escape + OWASP sanitize」雙層。
 * 兩者比較：</p>
 *
 * <ul>
 *   <li><strong>flexmark ESCAPE_HTML=true</strong>：將 raw HTML 的 {@code <}、{@code >} 轉義為 entities，
 *       讓使用者輸入的 {@code <script>} 等 tag 變成可讀文字（非可執行 HTML）。</li>
 *   <li><strong>OWASP 白名單剝除</strong>：HTML 解析後，凡不在白名單內的 element 與 attribute 一律移除。
 *       對允許的 {@code <a>} 等元素強制注入 {@code rel="nofollow noopener"}。</li>
 * </ul>
 *
 * <p>雙層的問題：flexmark escape 後，OWASP 收到的是純文字，無法識別並剝除真正的 HTML element。
 * 結果是 raw HTML（即便危險）會以 escaped text 形式留在輸出中。雖無 XSS 風險，但
 * 字串如 {@code onerror=...} 會以可讀文字呈現，反而造成 UX 與測試斷言複雜化。</p>
 *
 * <p>單層的優勢：OWASP 直接處理 HTML element，可精準剝除 dangerous tag 與 attribute。
 * Trade-off：使用者輸入未包覆在 backtick 內的 raw HTML（例如 plain text {@code <script>} 三個字）
 * 會被 OWASP 吃掉。這在留言場景中極少發生（討論程式時習慣用 inline code）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
public class CommentMarkdownRenderer {

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory sanitizer;

    public CommentMarkdownRenderer() {
        MutableDataSet options = new MutableDataSet();
        options.setFrom(ParserEmulationProfile.COMMONMARK);
        // 讓 flexmark 輸出 raw HTML（例如 <script>），由 OWASP 後續消毒
        options.set(HtmlRenderer.ESCAPE_HTML, false);
        options.set(HtmlRenderer.SUPPRESS_HTML, false);

        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();

        // OWASP 白名單：只允許安全元素
        // requireRelNofollowOnLinks() 強制 rel 含 "nofollow"
        // requireRelsOnLinks("noopener") 追加 "noopener"，合併結果為 rel="nofollow noopener"
        this.sanitizer = new HtmlPolicyBuilder()
                .allowElements("p", "br", "strong", "em", "code", "blockquote", "a")
                .allowUrlProtocols("http", "https")
                .allowAttributes("href").onElements("a")
                .requireRelNofollowOnLinks()
                .requireRelsOnLinks("noopener")
                .allowAttributes("target").matching(java.util.regex.Pattern.compile("_blank")).onElements("a")
                .toFactory();
    }

    /**
     * 渲染留言 Markdown 為安全 HTML。
     *
     * @param markdown 原始 Markdown，可為 null
     * @return 渲染後的 HTML；null/空字串輸入回傳空字串
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        Node doc = parser.parse(markdown);
        String rawHtml = renderer.render(doc);

        String sanitized = sanitizer.sanitize(rawHtml);

        // OWASP 允許 target 屬性但不自動注入，後處理只對「沒有 target」的 <a> 補上 target="_blank"
        // negative lookahead 避免重複注入造成 target="_blank" target="_blank" 之類無效 HTML
        sanitized = sanitized.replaceAll("(?i)(<a\\b(?![^>]*\\btarget\\s*=)[^>]*?)>", "$1 target=\"_blank\">");

        return sanitized;
    }
}
