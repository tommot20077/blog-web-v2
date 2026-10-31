package dowob.xyz.blog.architecture;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 「資料表 → 擁有它的模組」對照表（跨模組 UUID 重構終態，設計 {@code 2026-10-24-cross-module-uuid-design.md} §6）。
 *
 * <p>這是 {@link CrossModuleBoundaryTest}（SQL 文字不得存取他模組的表）與
 * {@link NoCrossModuleForeignKeyIT}（資料庫內不得有跨模組 FK）共用的<b>單一真相</b>。
 * 兩條守衛吃同一份對照表，才不會出現「SQL 守衛認得這張表、FK 守衛不認得」的缺口。</p>
 *
 * <p><b>對照依據</b>（2026-10-31 以 grep 各模組 mapper／repository 的實際 SQL 讀寫，並對照 {@code ai-docs/schema.md}）：
 * 每張表的 SQL 只出現在其 owner 模組的 mapper／repository／XML 中（唯一例外是下方的 tag 參照資料）。
 * 新增表（新 Flyway migration）時必須同步登記於此——{@link NoCrossModuleForeignKeyIT} 會斷言
 * migration 後 public schema 的每一張表都在本表內（或在 {@link #IGNORED_TABLES}），沒登記就紅。</p>
 *
 * @author Yuan
 * @version 1.0
 */
final class ModuleTableOwnership {

    /** 共用基礎設施（blog-infrastructure）擁有的表，不屬於任何業務模組 */
    static final String INFRASTRUCTURE = "infrastructure";

    /**
     * 資料表 → 擁有它的模組名（業務模組名為 {@code blog-module-*} 去掉前綴；共用表為 {@link #INFRASTRUCTURE}）。
     */
    static final Map<String, String> OWNER_BY_TABLE = Map.ofEntries(
            Map.entry("articles", "article"),
            Map.entry("categories", "article"),
            Map.entry("article_categories", "article"),
            Map.entry("comments", "comment"),
            Map.entry("comment_likes", "comment"),
            Map.entry("user_article_likes", "reading"),
            Map.entry("user_bookmarks", "reading"),
            Map.entry("user_highlights", "reading"),
            Map.entry("user_reading_progress", "reading"),
            Map.entry("article_versions", "version"),
            Map.entry("user_preferences", "version"),
            Map.entry("series", "series"),
            Map.entry("tags", "tag"),
            Map.entry("article_tags", "tag"),
            Map.entry("user_tag_follows", "tag"),
            Map.entry("file_metadata", "file"),
            Map.entry("users", "user"),
            Map.entry("verification_tokens", "user"),
            Map.entry("processed_events", INFRASTRUCTURE));

    /** 不屬於任何模組、不納入檢查的表（Flyway 自己的版本紀錄） */
    static final Set<String> IGNORED_TABLES = Set.of("flyway_schema_history");

    /**
     * tag 模組的參照資料表：{@code tags}、{@code article_tags}。
     *
     * <p><b>Yuan 2026-10-31 決定</b>：這兩張表維持 reference data——article 模組的 SQL 可以
     * {@code FROM}／{@code JOIN} 它們（見 {@link #TAG_REFERENCE_DATA_READERS}），其他模組一律不行。
     * 這是刻意保留的跨模組 SQL 耦合，只為避免文章列表查詢拆成 N+1 次 facade 呼叫。
     * <b>日後拆服務時，這個例外要改成 {@code TagFacade} 呼叫並從守衛移除。</b></p>
     *
     * <p>放行範圍僅限<b>唯讀</b>（{@code @Select}、XML {@code <select>}、不帶 {@code @Modifying} 的 {@code @Query}）；
     * 對這兩張表的寫入（INSERT／UPDATE／DELETE）仍只有 tag 模組可以做。</p>
     */
    static final Set<String> TAG_REFERENCE_DATA_TABLES = Set.of("tags", "article_tags");

    /** 可唯讀存取 {@link #TAG_REFERENCE_DATA_TABLES} 的非 owner 模組（Yuan 2026-10-31 決定，見該常數說明） */
    static final Set<String> TAG_REFERENCE_DATA_READERS = Set.of("article");

    private ModuleTableOwnership() {
    }

    /**
     * 指定模組是否可以在 SQL 中存取指定的表。
     *
     * @param module   發出 SQL 的模組名
     * @param table    被存取的表（必須在 {@link #OWNER_BY_TABLE} 內）
     * @param readOnly SQL 是否為唯讀（SELECT）
     * @return owner 本人一律可以；tag 參照資料表另允許 {@link #TAG_REFERENCE_DATA_READERS} 唯讀；其餘不行
     */
    static boolean mayAccess(String module, String table, boolean readOnly) {
        String owner = OWNER_BY_TABLE.get(table);
        if (owner == null) {
            throw new IllegalArgumentException("未登記於對照表的表: " + table);
        }
        if (owner.equals(module)) {
            return true;
        }
        return readOnly
                && TAG_REFERENCE_DATA_TABLES.contains(table)
                && TAG_REFERENCE_DATA_READERS.contains(module);
    }

    /**
     * 對照表中的所有業務模組名（排除共用的 {@link #INFRASTRUCTURE}），排序。
     *
     * @return 業務模組名
     */
    static Set<String> businessModules() {
        Set<String> modules = new TreeSet<>(OWNER_BY_TABLE.values());
        modules.remove(INFRASTRUCTURE);
        return modules;
    }
}
