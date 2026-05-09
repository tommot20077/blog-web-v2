package dowob.xyz.blog.module.comment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommentMarkdownRendererTest {

    private CommentMarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new CommentMarkdownRenderer();
    }

    @Test
    void render_bold_returnsStrongTag() {
        String html = renderer.render("**bold**");
        assertThat(html).contains("<strong>bold</strong>");
    }

    @Test
    void render_italic_returnsEmTag() {
        String html = renderer.render("*italic*");
        assertThat(html).contains("<em>italic</em>");
    }

    @Test
    void render_inlineCode_returnsCodeTag() {
        String html = renderer.render("use `Map.of()` here");
        assertThat(html).contains("<code>Map.of()</code>");
    }

    @Test
    void render_link_addsNofollowAndTargetBlank() {
        String html = renderer.render("[Google](https://google.com)");
        assertThat(html).contains("<a");
        assertThat(html).contains("href=\"https://google.com\"");
        // OWASP HtmlPolicyBuilder 輸出順序為 noopener nofollow（先 requireRelsOnLinks 再 requireRelNofollowOnLinks）
        assertThat(html).contains("nofollow");
        assertThat(html).contains("noopener");
        assertThat(html).contains("target=\"_blank\"");
    }

    @Test
    void render_blockquote_returnsBlockquoteTag() {
        String html = renderer.render("> quoted");
        assertThat(html).contains("<blockquote>");
    }

    @Test
    void render_image_isStrippedOut() {
        String html = renderer.render("![alt](image.png)");
        assertThat(html).doesNotContain("<img");
    }

    @Test
    void render_heading_isStrippedOut() {
        String html = renderer.render("# Heading\n\nbody");
        assertThat(html).doesNotContain("<h1");
        assertThat(html).contains("body");
    }

    @Test
    void render_fencedCodeBlock_isStrippedOut() {
        String html = renderer.render("```java\ncode\n```");
        assertThat(html).doesNotContain("<pre>");
    }

    @Test
    void render_table_isStrippedOut() {
        String html = renderer.render("| a | b |\n|---|---|\n| 1 | 2 |");
        assertThat(html).doesNotContain("<table>");
    }

    @Test
    void render_xssScript_isSanitized() {
        String html = renderer.render("hello <script>alert(1)</script> world");
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("hello");
        assertThat(html).contains("world");
    }

    @Test
    void render_javascriptUrl_isSanitized() {
        String html = renderer.render("[click](javascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void render_onerrorAttribute_isSanitized() {
        String html = renderer.render("<img src=x onerror=alert(1)>");
        // 安全斷言：onerror 不能以可執行屬性形式存在（不是字串本身）
        assertThat(html).doesNotContain("onerror=");
        assertThat(html).doesNotContain("<img");
    }

    @Test
    void render_svgOnloadEvent_isSanitized() {
        String html = renderer.render("<svg onload=alert(1)></svg>");
        assertThat(html).doesNotContain("<svg");
        assertThat(html).doesNotContain("onload=");
    }

    @Test
    void render_htmlEntityEncodedJavascriptUrl_isSanitized() {
        // 攻擊向量：用 HTML entity 偽裝 javascript: scheme
        // &#x6A; = 'j' → "&#x6A;avascript:alert(1)"
        String html = renderer.render("[click](&#x6A;avascript:alert(1))");
        assertThat(html).doesNotContain("javascript:");
        assertThat(html).doesNotContain("&#x6A;avascript:");
    }

    @Test
    void render_rawAnchorWithJavascriptHref_isSanitized() {
        // 攻擊向量：raw HTML <a> 而非 markdown link 語法
        String html = renderer.render("<a href=\"javascript:alert(1)\">click</a>");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void render_nullInput_returnsEmptyString() {
        assertThat(renderer.render(null)).isEqualTo("");
    }

    @Test
    void render_emptyInput_returnsEmptyString() {
        assertThat(renderer.render("")).isEqualTo("");
    }
}
