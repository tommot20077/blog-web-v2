package dowob.xyz.blog.module.article.service;

import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.html.AttributeProvider;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.html.IndependentAttributeProviderFactory;
import com.vladsch.flexmark.html.renderer.AttributablePart;
import com.vladsch.flexmark.html.renderer.LinkResolverContext;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import dowob.xyz.blog.module.article.model.dto.response.TocEntry;
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
        String html = renderer.render("# Title").html();
        assertThat(html).contains("<h1>Title</h1>");
    }

    @Test
    @DisplayName("正常：圖片（https）應被保留，src 屬性存在")
    void render_image_allowed() {
        String html = renderer.render("![alt](https://example.com/x.png)").html();
        assertThat(html).contains("<img");
        assertThat(html).contains("src=\"https://example.com/x.png\"");
    }

    @Test
    @DisplayName("正常：fenced code block 應渲染出 <pre> 與 <code>")
    void render_codeBlock_allowed() {
        String html = renderer.render("```java\nSystem.out.println();\n```").html();
        assertThat(html).contains("<pre>");
        assertThat(html).contains("<code");
    }

    @Test
    @DisplayName("正常：連結應強制加上 nofollow、noopener 與 target=_blank")
    void render_link_addsNofollowAndTargetBlank() {
        String html = renderer.render("[Google](https://google.com)").html();
        assertThat(html).contains("nofollow");
        assertThat(html).contains("noopener");
        assertThat(html).contains("target=\"_blank\"");
    }

    // ─── 安全：應被剝除的 ───

    @Test
    @DisplayName("安全：markdown 連結使用 javascript: URL 應被剝除")
    void render_javascriptUrlInMarkdownLink_isStripped() {
        String html = renderer.render("[click](javascript:alert(1))").html();
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("安全：markdown 圖片使用 javascript: URL 應被剝除")
    void render_javascriptUrlInImage_isStripped() {
        String html = renderer.render("![](javascript:alert(1))").html();
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("安全：raw <script> 標籤應被剝除，正常文字仍保留")
    void render_rawScriptTag_isStripped() {
        String html = renderer.render("hello <script>alert(1)</script> world").html();
        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("hello");
        assertThat(html).contains("world");
    }

    @Test
    @DisplayName("安全：<iframe> 應被剝除")
    void render_iframe_isStripped() {
        String html = renderer.render("<iframe src=\"https://evil.com\"></iframe>").html();
        assertThat(html).doesNotContain("<iframe");
    }

    @Test
    @DisplayName("安全：onerror 事件屬性應被剝除")
    void render_onerrorAttribute_isStripped() {
        String html = renderer.render("<img src=x onerror=alert(1)>").html();
        assertThat(html).doesNotContain("onerror=");
    }

    @Test
    @DisplayName("安全：<svg onload> 應被整體剝除")
    void render_svgOnload_isStripped() {
        String html = renderer.render("<svg onload=alert(1)></svg>").html();
        assertThat(html).doesNotContain("<svg");
        assertThat(html).doesNotContain("onload=");
    }

    @Test
    @DisplayName("安全：HTML entity 偽裝 javascript: URL 應被剝除")
    void render_htmlEntityJavascriptUrl_isStripped() {
        String html = renderer.render("[click](&#x6A;avascript:alert(1))").html();
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
    @DisplayName("邊界：空字串輸入應回傳空 HTML 與空 TOC 清單")
    void render_emptyInput_returnsEmptyResult() {
        RenderResult result = renderer.render("");
        assertThat(result.html()).isEqualTo("");
        assertThat(result.toc()).isNotNull().isEmpty();
    }

    // ─── Step 0：flexmark heading id API 驗證（特徵化測試） ───

    @Test
    @DisplayName("驗證：flexmark AttributeProvider（NODE part）可對 Heading 注入含 Unicode 的 id")
    void flexmarkAttributeProvider_injectsUnicodeHeadingId_apiCharacterization() {
        MutableDataSet options = new MutableDataSet();
        options.set(HtmlRenderer.ESCAPE_HTML, false);
        Parser parser = Parser.builder(options).build();
        HtmlRenderer htmlRenderer = HtmlRenderer.builder(options)
                .attributeProviderFactory(new IndependentAttributeProviderFactory() {
                    @Override
                    public AttributeProvider apply(LinkResolverContext context) {
                        return (node, part, attributes) -> {
                            if (part == AttributablePart.NODE
                                    && node instanceof Heading heading
                                    && heading.getLevel() == 2) {
                                attributes.replaceValue("id", "heading-安裝步驟");
                            }
                        };
                    }
                })
                .build();

        Node doc = parser.parse("## 安裝步驟");
        String html = htmlRenderer.render(doc);

        assertThat(html).contains("<h2 id=\"heading-安裝步驟\">");
    }

    // ─── TOC 產出與 heading id 注入 ───

    @Test
    @DisplayName("TOC：h2/h3 於 HTML 產生正確的 heading id")
    void render_h2AndH3_generateHeadingIds() {
        RenderResult result = renderer.render("## 安裝步驟\n\n### Port 被佔用");
        assertThat(result.html()).contains("<h2 id=\"heading-安裝步驟\">");
        assertThat(result.html()).contains("<h3 id=\"heading-port-被佔用\">");
    }

    @Test
    @DisplayName("TOC：依文件順序回傳 h2/h3 條目（id / text / level）")
    void render_h2AndH3_tocEntriesInDocumentOrder() {
        RenderResult result = renderer.render("## 安裝步驟\n\n### Port 被佔用");
        assertThat(result.toc()).containsExactly(
                new TocEntry("heading-安裝步驟", "安裝步驟", 2),
                new TocEntry("heading-port-被佔用", "Port 被佔用", 3));
    }

    @Test
    @DisplayName("TOC：中文標題 slug 不退化為空字串")
    void render_chineseHeading_slugNotDegradedToEmpty() {
        RenderResult result = renderer.render("## 安裝步驟");
        assertThat(result.toc()).hasSize(1);
        TocEntry entry = result.toc().get(0);
        assertThat(entry.id()).isEqualTo("heading-安裝步驟");
        assertThat(entry.id()).isNotEqualTo("heading-");
        assertThat(entry.text()).isEqualTo("安裝步驟");
    }

    @Test
    @DisplayName("TOC：同文重複標題以序號去重（heading-安裝步驟 / heading-安裝步驟-2）")
    void render_duplicateHeadings_deduplicated() {
        RenderResult result = renderer.render("## 安裝步驟\n\n## 安裝步驟");
        assertThat(result.toc()).extracting(TocEntry::id)
                .containsExactly("heading-安裝步驟", "heading-安裝步驟-2");
        assertThat(result.html()).contains("<h2 id=\"heading-安裝步驟\">");
        assertThat(result.html()).contains("<h2 id=\"heading-安裝步驟-2\">");
    }

    @Test
    @DisplayName("護欄：相對路徑 img src 不可被 sanitizer 剝除（檔案存取控制的內文圖片全靠它）")
    void render_relativeImageSrc_isPreserved() {
        /*
         * 檔案上傳改回傳相對路徑 /api/v1/files/{id}/content 後，內文圖片全部是相對 URL。
         * sanitizer 設了 allowUrlProtocols("http","https")——若這組設定連帶把「沒有協定」
         * 的相對 URL 一起剝掉，所有內文圖片都會渲染成沒有 src 的 <img>，
         * 整個檔案存取控制等於白做。此測試把「相對路徑放行」釘成回歸護欄。
         */
        RenderResult result = renderer.render(
                "![示意圖](/api/v1/files/09c03730-17b3-48a2-96cf-aa7da7d5c96d/content)");

        assertThat(result.html())
                .as("相對路徑的 img src 必須保留")
                .contains("src=\"/api/v1/files/09c03730-17b3-48a2-96cf-aa7da7d5c96d/content\"");
    }

    @Test
    @DisplayName("TOC：去重序號不可與另一個標題自然產生的 slug 相撞（heading-安裝步驟-2 衝突）")
    void render_headingIdCollidingWithDedupSuffix_producesUniqueIds() {
        /*
         * 「安裝步驟 2」的 slug 天然就是 安裝步驟-2，與「安裝步驟」第二次出現的去重序號
         * 產出的 id 完全相同。原實作只對 baseId 計數、不檢查最終 id 是否已被用掉，
         * 兩個標題會拿到同一個 id：HTML 出現重複 id（無效），且第二條 TOC 點下去會跳到第一條。
         */
        RenderResult result = renderer.render("## 安裝步驟\n\n## 安裝步驟 2\n\n## 安裝步驟");

        assertThat(result.toc()).extracting(TocEntry::id)
                .as("三個標題必須拿到三個互異的 id")
                .doesNotHaveDuplicates()
                .hasSize(3);
        result.toc().forEach(entry ->
                assertThat(result.html())
                        .as("TOC 的每個 id 都必須在 HTML 中實際存在，否則是死錨點")
                        .contains("id=\"" + entry.id() + "\""));
    }

    @Test
    @DisplayName("TOC：反向順序同樣不可相撞（先出現 heading-安裝步驟-2，後續去重需跳號）")
    void render_naturalSlugTakenBeforeDedup_producesUniqueIds() {
        RenderResult result = renderer.render("## 安裝步驟 2\n\n## 安裝步驟\n\n## 安裝步驟");

        assertThat(result.toc()).extracting(TocEntry::id).doesNotHaveDuplicates().hasSize(3);
        result.toc().forEach(entry ->
                assertThat(result.html()).contains("id=\"" + entry.id() + "\""));
    }

    @Test
    @DisplayName("TOC：h1 與 h4 不納入 TOC，僅收 h2/h3")
    void render_h1AndH4_notIncludedInToc() {
        RenderResult result = renderer.render("# 主標題\n\n## 章節\n\n#### 小節");
        assertThat(result.toc()).extracting(TocEntry::level).containsExactly(2);
        assertThat(result.toc()).extracting(TocEntry::text).containsExactly("章節");
    }

    @Test
    @DisplayName("TOC：無 heading 的文章回傳空清單（非 null）")
    void render_noHeading_returnsEmptyTocList() {
        RenderResult result = renderer.render("這是一段沒有任何標題的內文。");
        assertThat(result.toc()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("安全：使用者於 Markdown 內嵌 raw <h2 id=\"app\"> 的 id 應被 sanitizer 剝除")
    void render_userInjectedRawHtmlId_isStripped() {
        RenderResult result = renderer.render("<h2 id=\"app\">應用程式</h2>");
        assertThat(result.html()).doesNotContain("id=\"app\"");
        assertThat(result.html()).contains("應用程式");
    }

    @Test
    @DisplayName("安全：加入 id 白名單後既有 XSS 防護不回歸（script 仍被剝除）")
    void render_existingXssProtection_notRegressedAfterIdAllowlist() {
        RenderResult result = renderer.render("## 標題\n\nhello <script>alert(1)</script> world");
        assertThat(result.html()).doesNotContain("<script>");
        assertThat(result.html()).contains("hello");
        assertThat(result.html()).contains("world");
        assertThat(result.html()).contains("<h2 id=\"heading-標題\">");
    }

    // ─── T1a：buildHeadingId 去重序號 headroom 回歸 + 不變量 ───

    @Test
    @DisplayName("回歸：兩個 64 字重複標題，第二個 id 仍需符合 HEADING_ID_PATTERN")
    void render_twoDuplicate64CharHeadings_secondIdStillMatchesPattern() {
        String longTitle = "a".repeat(64);
        RenderResult result = renderer.render("## " + longTitle + "\n\n## " + longTitle);

        assertThat(result.toc()).hasSize(2);
        String firstId = result.toc().get(0).id();
        String secondId = result.toc().get(1).id();

        // 第一個 id 剛好卡在上限（64 個字元），不應有去重序號
        assertThat(firstId).isEqualTo("heading-" + longTitle);
        assertThat(firstId).matches(ArticleMarkdownRenderer.HEADING_ID_PATTERN);

        // 第二個 id 修前為 66 字元（64 + "-2"）而不符合 pattern；修後應截斷 base slug 讓總長度仍 <=64
        assertThat(secondId).matches(ArticleMarkdownRenderer.HEADING_ID_PATTERN);
        assertThat(secondId).endsWith("-2");

        // TOC 回傳的 id 必須與 HTML 中實際存活的 id 一致，否則 TOC 產生死錨點
        assertThat(result.html()).contains("id=\"" + firstId + "\"");
        assertThat(result.html()).contains("id=\"" + secondId + "\"");
    }

    @Test
    @DisplayName("不變量：長標題 + 高重複次數（含兩位數序號）+ fallback slug 下，所有 TOC id 皆符合 HEADING_ID_PATTERN 且與 HTML id 一致")
    void render_allProducedTocIds_matchHeadingIdPattern() {
        String longTitle = "測試標題".repeat(20); // 80 個 code point 的中文標題，觸發截斷路徑
        StringBuilder markdown = new StringBuilder();
        // 重複 11 次，讓去重序號跨過兩位數（-2 ... -11），驗證 headroom 隨序號位數動態調整
        for (int i = 0; i < 11; i++) {
            markdown.append("## ").append(longTitle).append("\n\n");
        }
        // 混入 fallback slug（純符號標題）情境的重複去重
        for (int i = 0; i < 3; i++) {
            markdown.append("## !!!\n\n");
        }

        RenderResult result = renderer.render(markdown.toString());

        assertThat(result.toc()).hasSize(14);
        for (TocEntry entry : result.toc()) {
            assertThat(entry.id()).matches(ArticleMarkdownRenderer.HEADING_ID_PATTERN);
            assertThat(result.html()).contains("id=\"" + entry.id() + "\"");
        }
    }
}
