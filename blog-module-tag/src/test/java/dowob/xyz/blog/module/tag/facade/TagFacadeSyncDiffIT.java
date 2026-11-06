package dowob.xyz.blog.module.tag.facade;

import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.tag.service.TagNormalizationService;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章標籤同步改為「差集」的整合測試（真實 Postgres，PERF-27）。
 *
 * <p>舊實作先刪光再逐筆重插：只改一個標籤也會重寫全部 {@code article_tags} 列。
 * 本測試以兩種證據證明差集生效：(1) 未變動列的系統欄位 {@code xmin} 不變（被刪除重插的列會拿到新的 xmin）；
 * (2) 同一交易內的 {@code pg_stat_xact_user_tables} 寫入計數（只反映當前交易，沒有統計延遲）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagFacade 文章標籤同步差集 整合測試（PERF-27）")
class TagFacadeSyncDiffIT extends TagCleanupITSupport {

    @Autowired private TagFacade tagFacade;
    @Autowired private TagNormalizationService tagNormalizationService;
    @Autowired private PlatformTransactionManager txManager;

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** tag_id → xmin（交易 id）。 */
    private Map<UUID, Long> xminByTag(UUID article) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query("SELECT tag_id, xmin::text::bigint AS x FROM article_tags WHERE article_id = ?",
                rs -> {
                    result.put(rs.getObject("tag_id", UUID.class), rs.getLong("x"));
                }, article);
        return result;
    }

    /** 目前累計的 {n_tup_ins, n_tup_del}（pg_stat_xact 會含同一連線尚未 flush 的先前交易計數，故一律取前後差）。 */
    private long[] writeCounters() {
        return jdbc.queryForObject(
                "SELECT n_tup_ins, n_tup_del FROM pg_stat_xact_user_tables WHERE relname = 'article_tags'",
                (rs, i) -> new long[]{rs.getLong(1), rs.getLong(2)});
    }

    /** 在單一交易內執行動作，回傳該動作造成的 {新增筆數, 刪除筆數}。 */
    private long[] writesDuring(Runnable action) {
        return tx().execute(s -> {
            long[] before = writeCounters();
            action.run();
            long[] after = writeCounters();
            return new long[]{after[0] - before[0], after[1] - before[1]};
        });
    }

    @Test
    @DisplayName("[A,B,C] → [A,B,D]：A、B 的列原封不動（xmin 不變），C 被刪、D 被新增")
    void syncArticleTags_changeOneTag_unchangedRowsUntouched() {
        UUID a = insertTag("tag-a", 0);
        UUID b = insertTag("tag-b", 0);
        UUID c = insertTag("tag-c", 0);
        UUID d = insertTag("tag-d", 0);
        UUID article = UUID.randomUUID();
        insertArticleTag(article, a);
        insertArticleTag(article, b);
        insertArticleTag(article, c);
        Map<UUID, Long> before = xminByTag(article);

        tx().executeWithoutResult(s -> tagFacade.syncArticleTags(article, List.of(a, b, d)));

        Map<UUID, Long> after = xminByTag(article);
        assertThat(after.keySet()).containsExactlyInAnyOrder(a, b, d);
        assertThat(after.get(a)).as("A 未變動，不應被刪除重插").isEqualTo(before.get(a));
        assertThat(after.get(b)).as("B 未變動，不應被刪除重插").isEqualTo(before.get(b));
    }

    @Test
    @DisplayName("只改一個標籤：同一交易內 article_tags 恰好 1 筆刪除、1 筆新增（舊實作為 3 刪 3 增）")
    void syncArticleTags_changeOneTag_writesOneDeleteAndOneInsert() {
        UUID a = insertTag("tag-a", 0);
        UUID b = insertTag("tag-b", 0);
        UUID c = insertTag("tag-c", 0);
        UUID d = insertTag("tag-d", 0);
        UUID article = UUID.randomUUID();
        insertArticleTag(article, a);
        insertArticleTag(article, b);
        insertArticleTag(article, c);

        long[] writes = writesDuring(() -> tagFacade.syncArticleTags(article, List.of(a, b, d)));

        assertThat(writes[0]).as("新增").isEqualTo(1);
        assertThat(writes[1]).as("刪除").isEqualTo(1);
    }

    @Test
    @DisplayName("新舊集合相同：零寫入，所有列的 xmin 不變")
    void syncArticleTags_sameSet_noWritesAtAll() {
        UUID a = insertTag("tag-a", 0);
        UUID b = insertTag("tag-b", 0);
        UUID article = UUID.randomUUID();
        insertArticleTag(article, a);
        insertArticleTag(article, b);
        Map<UUID, Long> before = xminByTag(article);

        long[] writes = writesDuring(() -> tagFacade.syncArticleTags(article, List.of(b, a)));

        assertThat(writes[0]).isZero();
        assertThat(writes[1]).isZero();
        assertThat(xminByTag(article)).isEqualTo(before);
    }

    @Test
    @DisplayName("全新文章一次批次新增多個標籤：全部寫入（驗證批次 INSERT ... SELECT 的真實 SQL）")
    void syncArticleTags_newArticle_batchInsertsAllTags() {
        List<UUID> tags = List.of(insertTag("t1", 0), insertTag("t2", 0), insertTag("t3", 0), insertTag("t4", 0));
        UUID article = UUID.randomUUID();

        long[] writes = writesDuring(() -> tagFacade.syncArticleTags(article, tags));

        assertThat(countArticleTags(article)).isEqualTo(4);
        assertThat(writes[0]).isEqualTo(4);
        assertThat(writes[1]).isZero();
    }

    @Test
    @DisplayName("清空（空清單）：只刪除既有關聯，全部移除")
    void syncArticleTags_emptyList_removesAll() {
        UUID a = insertTag("tag-a", 0);
        UUID b = insertTag("tag-b", 0);
        UUID article = UUID.randomUUID();
        insertArticleTag(article, a);
        insertArticleTag(article, b);

        tx().executeWithoutResult(s -> tagFacade.syncArticleTags(article, List.of()));

        assertThat(countArticleTags(article)).isZero();
    }

    @Test
    @DisplayName("findExistingBySlugs：一次查出已存在的 slug，查無者不在結果；空輸入不報錯")
    void findExistingBySlugs_returnsOnlyExisting() {
        UUID a = insertTag("alpha", 0);
        insertTag("beta", 0);

        Map<String, ?> found = tagNormalizationService.findExistingBySlugs(List.of("alpha", "missing", "alpha"));

        assertThat(found).containsOnlyKeys("alpha");
        assertThat(tagNormalizationService.findExistingBySlugs(List.of())).isEmpty();
        assertThat(a).isNotNull();
    }

    @Test
    @DisplayName("findOrCreateTags：既有標籤直接讀出（不新增列），缺少的才建立；回傳順序與輸入一致")
    void findOrCreateTags_mixedExistingAndNew_createsOnlyMissing() {
        UUID existing = insertTag("alpha", 0);

        List<TagInfo> result = tx().execute(s -> tagFacade.findOrCreateTags(List.of("zeta", "alpha")));

        assertThat(result).extracting(TagInfo::slug).containsExactly("zeta", "alpha");
        assertThat(result.get(1).id()).isEqualTo(existing);
        assertThat(countTags()).isEqualTo(2);
    }
}
