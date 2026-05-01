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

        // OWASP 允許 target 屬性但不自動注入，強制確保每個 <a> 含 target="_blank"
        sanitized = sanitized.replaceAll("(<a\\b[^>]*?)>", "$1 target=\"_blank\">");

        return sanitized;
    }
}
