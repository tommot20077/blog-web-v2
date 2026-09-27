package dowob.xyz.blog.module.article.model.dto.request;

import java.util.Locale;

/**
 * 公開文章列表的排序方式。
 *
 * <p>三種排序皆為 {@code articles} 本表欄位（{@code published_at} / {@code view_count} /
 * {@code comment_count}），不需要跨模組 JOIN；實際 SQL 見
 * {@code ArticleMapper#findPublishedPageByCriteria}，每種排序都附 {@code id DESC}
 * 作為 tie-breaker——鍵值相同時若無 tie-breaker，PostgreSQL 不保證
 * {@code LIMIT/OFFSET} 跨頁穩定，同一篇可能重複出現或整篇消失。tie-breaker 只保證同一份資料快照下
 * 順序確定；翻頁之間資料變動（新發布、計數回寫）時 OFFSET 分頁仍可能重複或遺漏。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public enum ArticleListSort {

    /** 依發布時間由新到舊（預設） */
    LATEST("latest"),

    /** 依瀏覽數由多到少 */
    POPULAR("popular"),

    /** 依留言數由多到少 */
    COMMENTED("commented");

    /** query string 中的值，亦為前端既有的排序鍵 */
    private final String key;

    /**
     * 建立排序方式。
     *
     * @param key query string 中的值
     */
    ArticleListSort(String key) {
        this.key = key;
    }

    /**
     * 取 query string 中的值。
     *
     * @return 小寫排序鍵
     */
    public String key() {
        return key;
    }

    /**
     * 由 query string 的值解析排序方式。
     *
     * <p>不分大小寫、容許前後空白；{@code null} 或未知值一律退回 {@link #LATEST}。
     * 採正規化而非拒絕——與 {@code PageQuery}（超出範圍即 400）刻意不同：分頁與多值篩選一旦被修正，
     * client 拿到的資料範圍就與請求不符；本參數只影響順序、不影響回傳哪些文章，
     * 拼錯的代價只是「看到預設排序」。</p>
     *
     * @param key query string 的 sort 值，可為 {@code null}
     * @return 排序方式，永不為 {@code null}
     */
    public static ArticleListSort fromKey(String key) {
        if (key == null) {
            return LATEST;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (ArticleListSort sort : values()) {
            if (sort.key.equals(normalized)) {
                return sort;
            }
        }
        return LATEST;
    }
}
