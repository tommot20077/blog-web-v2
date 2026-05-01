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
        assertThat(html).contains("rel=\"nofollow noopener\"");
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
        assertThat(html).doesNotContain("onerror");
        assertThat(html).doesNotContain("<img");
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
