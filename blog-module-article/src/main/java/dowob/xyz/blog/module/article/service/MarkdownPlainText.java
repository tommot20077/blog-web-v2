package dowob.xyz.blog.module.article.service;

import java.util.regex.Pattern;

/**
 * Markdown → 純文字（供 Elasticsearch 全文索引的 {@code content}）。
 *
 * <p><b>單一真相</b>：全量重建（{@code ArticleFacadeImpl} 的索引資料）與增量事件
 * （{@code ArticleEventPublisher} 的 {@code contentText}）都經由本類別轉換。兩者原本各有一份
 * 一模一樣的 {@code stripMarkdown}；若日後只改其中一份，同一篇文章經 reindex 與經事件索引
 * 會得到不同的內文。</p>
 *
 * <p><b>預編譯</b>（PERF-01）：原實作是 10 條 {@code String#replaceAll}，每篇文章每條都重新編譯一次 regex；
 * 全量重建時是「文章數 × 9」次編譯。這裡把 pattern 收成 {@code static final} 常數，只在類別載入時編譯一次。
 * 轉換規則與順序與原實作逐條相同（{@code MarkdownPlainTextTest} 以原輸出逐字鎖定）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class MarkdownPlainText {

    /** fenced code block（含內容）整段移除 */
    private static final Pattern FENCED_CODE = Pattern.compile("```[\\s\\S]*?```");

    /** 行內 code（含內容）移除 */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");

    /** 行首的標題井號 */
    private static final Pattern HEADING_MARKER = Pattern.compile("(?m)^#{1,6}\\s*");

    /** 星號強調（* / **），保留內文 */
    private static final Pattern ASTERISK_EMPHASIS = Pattern.compile("\\*{1,2}([^*]+)\\*{1,2}");

    /** 底線強調（_ / __），保留內文 */
    private static final Pattern UNDERSCORE_EMPHASIS = Pattern.compile("_{1,2}([^_]+)_{1,2}");

    /** 圖片整段移除（不保留 alt） */
    private static final Pattern IMAGE = Pattern.compile("!\\[[^]]*]\\([^)]*\\)");

    /** 連結只保留文字 */
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");

    /** 獨立一行的水平分隔線 */
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("(?m)^[-*_]{3,}$");

    /** 兩個以上的連續空白（含換行） */
    private static final Pattern MULTI_WHITESPACE = Pattern.compile("\\s{2,}");

    /**
     * 靜態工具類別，不提供實例化。
     */
    private MarkdownPlainText() {
    }

    /**
     * 去除 Markdown 格式，回傳純文字。
     *
     * @param markdown Markdown 原文；可為 {@code null}
     * @return 純文字；{@code null} 輸入回傳空字串
     */
    public static String strip(String markdown) {
        if (markdown == null) {
            return "";
        }
        String text = FENCED_CODE.matcher(markdown).replaceAll("");
        text = INLINE_CODE.matcher(text).replaceAll("");
        text = HEADING_MARKER.matcher(text).replaceAll("");
        text = ASTERISK_EMPHASIS.matcher(text).replaceAll("$1");
        text = UNDERSCORE_EMPHASIS.matcher(text).replaceAll("$1");
        text = IMAGE.matcher(text).replaceAll("");
        text = LINK.matcher(text).replaceAll("$1");
        text = HORIZONTAL_RULE.matcher(text).replaceAll("");
        text = MULTI_WHITESPACE.matcher(text).replaceAll(" ");
        return text.trim();
    }
}
