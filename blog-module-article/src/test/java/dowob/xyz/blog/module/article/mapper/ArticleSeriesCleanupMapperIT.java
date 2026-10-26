package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleSeriesCleanupMapper} 對真實 PostgreSQL 的整合測試：校正排程用的 keyset 分頁、受影響文章的查詢，
 * 以及清空歸屬（只清歸屬、不刪文章、version 遞增、updated_at 不動）。
 *
 * <p>keyset 的排序與比較必須由資料庫的 uuid 型別（位元組順序）決定：Java 的 {@code UUID.compareTo} 以有號 long
 * 比較，會把 {@code ffffffff-…} 排在 {@code 00000000-…} 之前，兩者並不一致。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleSeriesCleanupMapper 整合測試")
class ArticleSeriesCleanupMapperIT extends ArticleSeriesITSupport {

    @Autowired private ArticleSeriesCleanupMapper mapper;

    private static final UUID LOWEST = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final UUID HIGHEST = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    /** 依資料庫（位元組）順序排序：小寫十六進位字串的字典序與位元組順序一致。 */
    private static List<UUID> byteOrder(List<UUID> uuids) {
        return uuids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    @Test
    @DisplayName("findDistinctSeriesUuidsAfter：以 limit=2 分頁走完 5 個系列，依位元組順序、含 00000000-… 與 ffffffff-…，不重複不遺漏")
    void keysetPagination_walksAllSeriesInByteOrder() {
        List<UUID> series = List.of(HIGHEST, LOWEST, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (UUID s : series) {
            insertArticle(s, 1, "PUBLISHED");
            insertArticle(s, 2, "PUBLISHED");
        }
        insertArticle(null, null, "PUBLISHED");

        List<UUID> collected = new ArrayList<>();
        UUID cursor = null;
        for (int page = 0; page < 10; page++) {
            List<UUID> rows = mapper.findDistinctSeriesUuidsAfter(cursor, 2).stream().map(UUID::fromString).toList();
            if (rows.isEmpty()) {
                break;
            }
            assertThat(rows.size()).isLessThanOrEqualTo(2);
            collected.addAll(rows);
            cursor = rows.get(rows.size() - 1);
        }

        assertThat(collected).containsExactlyElementsOf(byteOrder(series));
        assertThat(collected.get(0)).isEqualTo(LOWEST);
        assertThat(collected.get(collected.size() - 1)).isEqualTo(HIGHEST);
    }

    @Test
    @DisplayName("findDistinctSeriesUuidsAfter：游標為 null 從頭開始；游標之後沒有資料回空")
    void keyset_nullCursorStartsFromBeginning_endCursorReturnsEmpty() {
        insertArticle(LOWEST, 1, "PUBLISHED");
        insertArticle(HIGHEST, 1, "PUBLISHED");

        assertThat(mapper.findDistinctSeriesUuidsAfter(null, 10)).containsExactly(LOWEST.toString(), HIGHEST.toString());
        assertThat(mapper.findDistinctSeriesUuidsAfter(HIGHEST, 10)).isEmpty();
    }

    @Test
    @DisplayName("findMembersBySeriesUuids：回傳指定系列的成員文章（articleUuid、seriesUuid、position），其他系列與無系列文章不在內")
    void findMembers_returnsOnlyRequestedSeries() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID m1 = insertArticle(target, 3, "PUBLISHED");
        UUID m2 = insertArticle(target, 7, "DRAFT");
        insertArticle(other, 1, "PUBLISHED");
        insertArticle(null, null, "PUBLISHED");

        List<ArticleSeriesCleanupMapper.StaleMemberRow> rows = mapper.findMembersBySeriesUuids(List.of(target));

        assertThat(rows).extracting(ArticleSeriesCleanupMapper.StaleMemberRow::articleUuid)
                .containsExactlyInAnyOrder(m1, m2);
        assertThat(rows).extracting(ArticleSeriesCleanupMapper.StaleMemberRow::seriesUuid).containsOnly(target);
        assertThat(rows).extracting(ArticleSeriesCleanupMapper.StaleMemberRow::seriesPosition)
                .containsExactlyInAnyOrder(3, 7);
    }

    @Test
    @DisplayName("clearSeriesByUuid／clearSeriesByUuids：只清 series_uuid 與 series_position、version 遞增、updated_at 不動，文章全部保留")
    void clear_clearsMembershipOnly_bumpsVersion_keepsArticles() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        java.time.LocalDateTime fixed = java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        UUID member = insertArticleWithAudit(target, 2, 4, fixed);
        UUID member2 = insertArticleWithAudit(other, 2, 4, fixed);
        UUID untouched = insertArticleWithAudit(UUID.randomUUID(), 9, 4, fixed);

        assertThat(mapper.clearSeriesByUuid(target)).isEqualTo(1);
        assertThat(mapper.clearSeriesByUuids(List.of(other))).isEqualTo(1);

        assertThat(seriesUuidOf(member)).isNull();
        assertThat(positionOf(member)).isNull();
        assertThat(versionOf(member)).isEqualTo(5);
        assertThat(updatedAtOf(member)).isEqualTo(fixed);
        assertThat(seriesUuidOf(member2)).isNull();
        assertThat(versionOf(untouched)).isEqualTo(4);
        assertThat(countArticles()).isEqualTo(3);
    }
}
