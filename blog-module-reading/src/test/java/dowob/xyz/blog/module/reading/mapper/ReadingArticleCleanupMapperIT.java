package dowob.xyz.blog.module.reading.mapper;

import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReadingArticleCleanupMapper} 整合測試（真實 Postgres）：四張表各自的 keyset 分頁
 * （排序／去重／游標）與批次刪除。
 *
 * <p>V26／V27 起四張表都沒有指向 {@code articles} 的 FK，測試直接以任意 UUID 寫入，不需要真的建立文章。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReadingArticleCleanupMapper 整合測試")
class ReadingArticleCleanupMapperIT extends ReadingCleanupITSupport {

    /** PostgreSQL uuid 的排序：位元組由高到低、以無號數比較（與 {@link UUID#compareTo} 不同） */
    private static final Comparator<UUID> PG_UUID_ORDER = Comparator
            .comparingLong((UUID u) -> u.getMostSignificantBits() ^ Long.MIN_VALUE)
            .thenComparingLong(u -> u.getLeastSignificantBits() ^ Long.MIN_VALUE);

    @Autowired private ReadingArticleCleanupMapper mapper;

    /**
     * 驗證 keyset 分頁：去重、依資料庫 uuid 順序、游標不含自身、最後一頁不足 limit。
     *
     * <p>固定兩個極值 UUID：Java 的 {@code UUID.compareTo}（高低 64 位元各自以有號數比較）會把 ffff... 排在 0000...01 之前，
     * 而 PostgreSQL 的 uuid 以無號位元組比較，排在其後——兩者順序必然不同，才能驗出排序確實由資料庫決定。</p>
     */
    private void assertKeysetPaging(java.util.function.Consumer<UUID> writer,
                                    BiFunction<UUID, Integer, List<String>> pager) {
        UUID lowest = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID highest = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        assertThat(highest.compareTo(lowest)).as("前置條件：Java 排序與 PG 排序不同").isNegative();
        List<UUID> expected = new ArrayList<>(List.of(lowest, highest));
        for (int i = 0; i < 3; i++) {
            expected.add(UUID.randomUUID());
        }
        for (UUID uuid : expected) {
            writer.accept(uuid);
        }
        writer.accept(expected.get(2)); // 同一篇被兩個使用者參照，結果只能出現一次（writer 內以不同使用者寫入）
        expected.sort(PG_UUID_ORDER);
        assertThat(expected.get(0)).isEqualTo(lowest);
        assertThat(expected.get(expected.size() - 1)).isEqualTo(highest);

        List<String> page1 = pager.apply(null, 2);
        List<String> page2 = pager.apply(UUID.fromString(page1.get(1)), 2);
        List<String> page3 = pager.apply(UUID.fromString(page2.get(1)), 2);
        List<String> page4 = pager.apply(UUID.fromString(page3.get(0)), 2);

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page3).as("最後一頁不足 limit").hasSize(1);
        assertThat(page4).as("游標已在最後一筆之後").isEmpty();
        List<String> all = new ArrayList<>(page1);
        all.addAll(page2);
        all.addAll(page3);
        assertThat(all.stream().map(UUID::fromString).toList()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("findDistinctLikeArticleUuidsAfter：去重、依資料庫 uuid 順序分頁，游標不含自身")
    void findDistinctLikeArticleUuidsAfter_pagesDistinctInDatabaseOrder() {
        int[] writes = {0};
        assertKeysetPaging(
                article -> insertLike(writes[0]++ % 2 == 0 ? USER_UUID : OTHER_USER_UUID, article),
                mapper::findDistinctLikeArticleUuidsAfter);
    }

    @Test
    @DisplayName("findDistinctBookmarkArticleUuidsAfter：去重、依資料庫 uuid 順序分頁，游標不含自身")
    void findDistinctBookmarkArticleUuidsAfter_pagesDistinctInDatabaseOrder() {
        int[] writes = {0};
        assertKeysetPaging(
                article -> insertBookmark(writes[0]++ % 2 == 0 ? USER_UUID : OTHER_USER_UUID, article),
                mapper::findDistinctBookmarkArticleUuidsAfter);
    }

    @Test
    @DisplayName("deleteLikesByArticleUuid／deleteBookmarksByArticleUuid：只刪該文章在各自那張表的列")
    void deleteByArticleUuid_deletesOnlyThatArticleInEachTable() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertLike(USER_UUID, target);
        insertLike(OTHER_USER_UUID, target);
        insertLike(USER_UUID, other);
        insertBookmark(USER_UUID, target);
        insertBookmark(USER_UUID, other);

        assertThat(mapper.deleteLikesByArticleUuid(target)).isEqualTo(2);
        assertThat(countLikes(target)).isZero();
        assertThat(countBookmarks(target)).as("刪按讚不得動到收藏").isEqualTo(1);

        assertThat(mapper.deleteBookmarksByArticleUuid(target)).isEqualTo(1);
        assertThat(countBookmarks(target)).isZero();
        assertThat(countLikes(other)).isEqualTo(1);
        assertThat(countBookmarks(other)).isEqualTo(1);
        assertThat(mapper.deleteLikesByArticleUuid(target)).as("天然冪等").isZero();
    }

    @Test
    @DisplayName("deleteLikesByArticleUuids／deleteBookmarksByArticleUuids：一次刪除多篇文章的列，其餘保留")
    void deleteByArticleUuids_deletesOnlyGivenArticles() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID keep = UUID.randomUUID();
        insertLike(USER_UUID, a);
        insertLike(USER_UUID, b);
        insertLike(OTHER_USER_UUID, b);
        insertLike(USER_UUID, keep);
        insertBookmark(USER_UUID, a);
        insertBookmark(USER_UUID, b);
        insertBookmark(USER_UUID, keep);

        assertThat(mapper.deleteLikesByArticleUuids(List.of(a, b))).isEqualTo(3);
        assertThat(mapper.deleteBookmarksByArticleUuids(List.of(a, b))).isEqualTo(2);

        assertThat(countLikes(a) + countLikes(b)).isZero();
        assertThat(countBookmarks(a) + countBookmarks(b)).isZero();
        assertThat(countLikes(keep)).isEqualTo(1);
        assertThat(countBookmarks(keep)).isEqualTo(1);
    }

    @Test
    @DisplayName("findDistinctHighlightArticleUuidsAfter：去重、依資料庫 uuid 順序分頁，游標不含自身")
    void findDistinctHighlightArticleUuidsAfter_pagesDistinctInDatabaseOrder() {
        int[] writes = {0};
        assertKeysetPaging(
                article -> insertHighlight(writes[0]++ % 2 == 0 ? USER_UUID : OTHER_USER_UUID, article),
                mapper::findDistinctHighlightArticleUuidsAfter);
    }

    @Test
    @DisplayName("findDistinctProgressArticleUuidsAfter：去重、依資料庫 uuid 順序分頁，游標不含自身")
    void findDistinctProgressArticleUuidsAfter_pagesDistinctInDatabaseOrder() {
        int[] writes = {0};
        assertKeysetPaging(
                article -> insertProgress(writes[0]++ % 2 == 0 ? USER_UUID : OTHER_USER_UUID, article),
                mapper::findDistinctProgressArticleUuidsAfter);
    }

    @Test
    @DisplayName("deleteHighlightsByArticleUuid／deleteProgressByArticleUuid：只刪該文章在各自那張表的列")
    void deleteHighlightsAndProgressByArticleUuid_deletesOnlyThatArticleInEachTable() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertHighlight(USER_UUID, target);
        insertHighlight(USER_UUID, target);
        insertHighlight(USER_UUID, other);
        insertProgress(USER_UUID, target);
        insertProgress(OTHER_USER_UUID, target);
        insertProgress(USER_UUID, other);

        assertThat(mapper.deleteHighlightsByArticleUuid(target)).isEqualTo(2);
        assertThat(countHighlights(target)).isZero();
        assertThat(countProgress(target)).as("刪劃線不得動到閱讀進度").isEqualTo(2);

        assertThat(mapper.deleteProgressByArticleUuid(target)).isEqualTo(2);
        assertThat(countProgress(target)).isZero();
        assertThat(countHighlights(other)).isEqualTo(1);
        assertThat(countProgress(other)).isEqualTo(1);
        assertThat(mapper.deleteHighlightsByArticleUuid(target)).as("天然冪等").isZero();
        assertThat(mapper.deleteProgressByArticleUuid(target)).as("天然冪等").isZero();
    }

    @Test
    @DisplayName("deleteHighlightsByArticleUuids／deleteProgressByArticleUuids：一次刪除多篇文章的列，其餘保留")
    void deleteHighlightsAndProgressByArticleUuids_deletesOnlyGivenArticles() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID keep = UUID.randomUUID();
        insertHighlight(USER_UUID, a);
        insertHighlight(USER_UUID, b);
        insertHighlight(OTHER_USER_UUID, b);
        insertHighlight(USER_UUID, keep);
        insertProgress(USER_UUID, a);
        insertProgress(USER_UUID, b);
        insertProgress(USER_UUID, keep);

        assertThat(mapper.deleteHighlightsByArticleUuids(List.of(a, b))).isEqualTo(3);
        assertThat(mapper.deleteProgressByArticleUuids(List.of(a, b))).isEqualTo(2);

        assertThat(countHighlights(a) + countHighlights(b)).isZero();
        assertThat(countProgress(a) + countProgress(b)).isZero();
        assertThat(countHighlights(keep)).isEqualTo(1);
        assertThat(countProgress(keep)).isEqualTo(1);
    }
}
