package dowob.xyz.blog.module.article.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarkdownPlainText} 單元測試
 *
 * <p>全量重建（{@code ArticleFacadeImpl}）與增量事件（{@code ArticleEventPublisher}）原本各有一份
 * 一模一樣的 {@code stripMarkdown}（10 條未預編譯的 {@code replaceAll}）。兩條路徑寫進索引的
 * {@code content} 必須同源，否則同一篇文章經 reindex 與經事件索引會得到不同的內文；
 * 本測試鎖定共用元件的每一條轉換規則（PERF-01）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("MarkdownPlainText 單元測試")
class MarkdownPlainTextTest {

    @Test
    @DisplayName("null 回傳空字串")
    void strip_null_returnsEmpty() {
        assertThat(MarkdownPlainText.strip(null)).isEmpty();
    }

    @Test
    @DisplayName("fenced code block 整段移除")
    void strip_fencedCodeBlock_removed() {
        assertThat(MarkdownPlainText.strip("前文\n```java\nint x = 1;\n```\n後文")).isEqualTo("前文 後文");
    }

    @Test
    @DisplayName("行內 code 移除")
    void strip_inlineCode_removed() {
        assertThat(MarkdownPlainText.strip("呼叫 `foo()` 即可")).isEqualTo("呼叫 即可");
    }

    @Test
    @DisplayName("標題井號移除，保留標題文字")
    void strip_headingMarkers_removed() {
        assertThat(MarkdownPlainText.strip("# 大標\n### 小標")).isEqualTo("大標\n小標");
    }

    @Test
    @DisplayName("星號強調（* 與 **）解開，保留文字")
    void strip_asteriskEmphasis_unwrapped() {
        assertThat(MarkdownPlainText.strip("這是 **粗體** 與 *斜體*")).isEqualTo("這是 粗體 與 斜體");
    }

    @Test
    @DisplayName("底線強調（_ 與 __）解開，保留文字")
    void strip_underscoreEmphasis_unwrapped() {
        assertThat(MarkdownPlainText.strip("這是 __粗體__ 與 _斜體_")).isEqualTo("這是 粗體 與 斜體");
    }

    @Test
    @DisplayName("圖片整段移除（不留 alt 文字）")
    void strip_image_removed() {
        assertThat(MarkdownPlainText.strip("看圖 ![示意圖](https://x/a.png) 結束")).isEqualTo("看圖 結束");
    }

    @Test
    @DisplayName("連結只保留文字")
    void strip_link_keepsText() {
        assertThat(MarkdownPlainText.strip("請見 [官方文件](https://x/doc) 說明")).isEqualTo("請見 官方文件 說明");
    }

    @Test
    @DisplayName("水平分隔線移除")
    void strip_horizontalRule_removed() {
        assertThat(MarkdownPlainText.strip("上段\n---\n下段")).isEqualTo("上段 下段");
    }

    @Test
    @DisplayName("連續空白壓成一個，並去除頭尾空白")
    void strip_whitespace_collapsedAndTrimmed() {
        assertThat(MarkdownPlainText.strip("  a    b  ")).isEqualTo("a b");
    }

    @Test
    @DisplayName("綜合範例：與原本兩份 stripMarkdown 的輸出逐字一致")
    void strip_compositeDocument_matchesLegacyOutput() {
        String markdown = "# 標題\n\n內文含 **粗體**、`code` 與 [連結](https://x)。\n\n```\nblock\n```\n\n![圖](u)\n";

        assertThat(MarkdownPlainText.strip(markdown)).isEqualTo("標題 內文含 粗體、 與 連結。");
    }

    @Test
    @DisplayName("正則一律為預編譯的 static final Pattern 常數（PERF-01：不在每篇文章重新編譯 10 條 regex）")
    void patterns_arePrecompiledConstants() {
        long patternConstants = Arrays.stream(MarkdownPlainText.class.getDeclaredFields())
                .filter(f -> f.getType() == Pattern.class)
                .filter(MarkdownPlainTextTest::isStaticFinal)
                .count();

        assertThat(patternConstants).isGreaterThanOrEqualTo(9);
    }

    private static boolean isStaticFinal(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);
    }
}
