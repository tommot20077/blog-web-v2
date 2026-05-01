package dowob.xyz.blog.module.article.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleMarkdownRenderer 安全性與功能單元測試。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMarkdownRenderer 單元測試")
class ArticleMarkdownRendererTest {

    private ArticleMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ArticleMarkdownRenderer();
    }

    // ─── 允許的語法 ───

    @Test
    @DisplayName("正常：標題 h1 應被保留")
    void render_heading_allowed() {
        String html = renderer.render("# Title");
        assertThat(html).contains("<h1>Title</h1>");
    }

    @Test
    @DisplayName("正常：圖片（https）應被保留，src 屬性存在")
    void render_image_allowed() {
        String html = renderer.render("![alt](https://example.com/x.png)");
        assertThat(html).contains("<img");
        assertThat(html).contains("src=\"https://example.com/x.png\"");
    }

    @Test
    @DisplayName("正常：fenced code block 應渲染出 <pre> 與 <code>")
    void render_codeBlock_allowed() {
        String html = renderer.render("```java\nSystem.out.println();\n```");
        assertThat(html).contains("<pre>");
        assertThat(html).contains("<code");
    }

    @Test
    @DisplayName("正常：連結應強制加上 nofollow、noopener 與 target=_blank")
    void render_link_addsNofollowAndTargetBlank() {
        String html = renderer.render("[Google](https://google.com)");
        assertThat(html).contains("nofollow");
        assertThat(html).contains("noopener");
        assertThat(html).contains("target=\"_blank\"");
    }

    // ─── 安全：應被剝除的 ───

    @Test
    @DisplayName("安全：markdown 連結使用 javascript: URL 應被剝除")
    void render_javascriptUrlInMarkdownLink_isStripped() {
        String html = renderer.render("[click](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("安全：markdown 圖片使用 javascript: URL 應被剝除")
    void render_javascriptUrlInImage_isStripped() {
        String html = renderer.render("![](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("安全：raw <script> 標籤應被剝除，正常文字仍保留")
    void render_rawScriptTag_isStripped() {
        String html = renderer.render("hello <script>alert(1)</script> world");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("hello");
        assertThat(html).contains("world");
    }

    @Test
    @DisplayName("安全：<iframe> 應被剝除")
    void render_iframe_isStripped() {
        String html = renderer.render("<iframe src=\"https://evil.com\"></iframe>");
        assertThat(html).doesNotContain("<iframe");
    }

    @Test
    @DisplayName("安全：onerror 事件屬性應被剝除")
    void render_onerrorAttribute_isStripped() {
        String html = renderer.render("<img src=x onerror=alert(1)>");
        assertThat(html).doesNotContain("onerror=");
    }

    @Test
    @DisplayName("安全：<svg onload> 應被整體剝除")
    void render_svgOnload_isStripped() {
        String html = renderer.render("<svg onload=alert(1)></svg>");
        assertThat(html).doesNotContain("<svg");
        assertThat(html).doesNotContain("onload=");
    }

    @Test
    @DisplayName("安全：HTML entity 偽裝 javascript: URL 應被剝除")
    void render_htmlEntityJavascriptUrl_isStripped() {
        String html = renderer.render("[click](&#x6A;avascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    // ─── plain text 提取 ───

    @Test
    @DisplayName("正常：toPlainText 應剝除 Markdown 語法，保留純文字")
    void toPlainText_stripsMarkdownSyntax() {
        String text = renderer.toPlainText("**bold** and *italic*");
        assertThat(text).contains("bold");
        assertThat(text).contains("italic");
        assertThat(text).doesNotContain("**");
        assertThat(text).doesNotContain("*italic");
    }

    // ─── null / empty 邊界 ───

    @Test
    @DisplayName("邊界：null 輸入應回傳 null")
    void render_nullInput_returnsNull() {
        assertThat(renderer.render(null)).isNull();
    }

    @Test
    @DisplayName("邊界：空字串輸入應回傳空字串")
    void render_emptyInput_returnsEmpty() {
        assertThat(renderer.render("")).isEqualTo("");
    }
}
