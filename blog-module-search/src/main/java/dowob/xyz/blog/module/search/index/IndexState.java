package dowob.xyz.blog.module.search.index;

import java.util.List;

/**
 * {@code blog_articles} 這個名稱在 Elasticsearch 中目前的樣子。
 *
 * <p>讀寫一律透過 alias {@code blog_articles}，背後是版本化的實體索引（{@code blog_articles_v*}）。
 * 但既有環境（開發機、測試環境、本次部署前的正式環境）的 {@code blog_articles} 可能是實體索引，
 * 也可能根本不存在；初始化與重建都要先判斷是哪一種，才能決定 alias 切換要怎麼送。</p>
 *
 * @param kind         名稱的型態
 * @param aliasTargets {@link Kind#ALIAS} 時為 alias 指向的實體索引（依名稱排序）；其餘型態為空清單
 * @author Yuan
 * @version 1.0
 */
public record IndexState(Kind kind, List<String> aliasTargets) {

    /**
     * {@code blog_articles} 名稱的型態。
     */
    public enum Kind {
        /** 不存在（全新環境） */
        ABSENT,
        /** 是實體索引而不是 alias（本次改版前建立的既有環境） */
        CONCRETE,
        /** 是 alias（正常狀態；指向一個實體索引，異常時可能多於一個） */
        ALIAS
    }

    /**
     * 以防禦性複製建立。
     *
     * @param kind         名稱的型態
     * @param aliasTargets alias 指向的實體索引
     */
    public IndexState {
        aliasTargets = aliasTargets == null ? List.of() : List.copyOf(aliasTargets);
    }

    /**
     * 名稱不存在。
     *
     * @return 狀態
     */
    public static IndexState absent() {
        return new IndexState(Kind.ABSENT, List.of());
    }

    /**
     * 名稱是實體索引。
     *
     * @return 狀態
     */
    public static IndexState concrete() {
        return new IndexState(Kind.CONCRETE, List.of());
    }

    /**
     * 名稱是 alias。
     *
     * @param targets alias 指向的實體索引
     * @return 狀態
     */
    public static IndexState alias(List<String> targets) {
        return new IndexState(Kind.ALIAS, targets);
    }
}
