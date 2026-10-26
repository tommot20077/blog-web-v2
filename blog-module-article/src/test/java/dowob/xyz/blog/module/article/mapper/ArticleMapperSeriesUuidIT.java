package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleMapper} 以 {@code series_uuid} 為鍵的 SQL，對真實 PostgreSQL 的整合測試（M6-b）。
 *
 * <p>V32 起 {@code articles} 以 {@code series_uuid}（可為 NULL、無 FK）儲存系列歸屬，原本以 {@code series_id}
 * 為鍵的列表、條件式寫入（RACE-14）、計數與 prev／next 導覽全部改以 UUID 查詢；語意與改造前相同。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMapper series_uuid SQL 整合測試")
class ArticleMapperSeriesUuidIT extends ArticleSeriesITSupport {

    @Autowired private ArticleMapper mapper;

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    // ─── 列表 ───

    @Test
    @DisplayName("findBySeriesUuidOrderByPosition：只回傳該系列的文章（不限狀態），依 series_position 升冪")
    void findBySeriesUuidOrderByPosition_returnsOnlyThatSeriesInPositionOrder() {
        UUID series = UUID.randomUUID();
        UUID third = insertArticle(series, 3, "PUBLISHED");
        UUID first = insertArticle(series, 1, "DRAFT");
        UUID second = insertArticle(series, 2, "PUBLISHED");
        insertArticle(UUID.randomUUID(), 1, "PUBLISHED");
        insertArticle(null, null, "PUBLISHED");

        List<Article> result = mapper.findBySeriesUuidOrderByPosition(series);

        assertThat(result).extracting(Article::getUuid).containsExactly(first, second, third);
        assertThat(result).extracting(Article::getSeriesUuid).containsOnly(series);
        assertThat(result).extracting(Article::getSeriesPosition).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("findBySeriesUuidOrderByPosition：查無的系列回傳空清單")
    void findBySeriesUuidOrderByPosition_unknownSeries_isEmpty() {
        insertArticle(UUID.randomUUID(), 1, "PUBLISHED");

        assertThat(mapper.findBySeriesUuidOrderByPosition(UUID.randomUUID())).isEmpty();
    }

    // ─── 條件式寫入（RACE-14） ───

    @Test
    @DisplayName("assignToSeriesIfUnassigned：文章目前無系列 → 影響 1 列，寫入 series_uuid／position、version+1、updated_at 為傳入時間")
    void assignToSeriesIfUnassigned_whenUnassigned_assignsAndBumpsAudit() {
        UUID article = insertArticle(null, null, "PUBLISHED");
        int versionBefore = versionOf(article);
        UUID series = UUID.randomUUID();
        LocalDateTime given = LocalDateTime.of(2031, 5, 6, 7, 8, 9);

        int affected = mapper.assignToSeriesIfUnassigned(article, series, 4, given);

        assertThat(affected).isEqualTo(1);
        assertThat(seriesUuidOf(article)).isEqualTo(series);
        assertThat(positionOf(article)).isEqualTo(4);
        assertThat(versionOf(article)).isEqualTo(versionBefore + 1);
        assertThat(updatedAtOf(article)).isEqualTo(given);
    }

    @Test
    @DisplayName("assignToSeriesIfUnassigned：文章已屬於某系列（含同一個）→ 影響 0 列，原歸屬不變")
    void assignToSeriesIfUnassigned_whenAlreadyAssigned_changesNothing() {
        UUID original = UUID.randomUUID();
        UUID article = insertArticle(original, 2, "PUBLISHED");

        assertThat(mapper.assignToSeriesIfUnassigned(article, UUID.randomUUID(), 9, now())).isZero();
        assertThat(mapper.assignToSeriesIfUnassigned(article, original, 9, now())).isZero();

        assertThat(seriesUuidOf(article)).isEqualTo(original);
        assertThat(positionOf(article)).isEqualTo(2);
    }

    @Test
    @DisplayName("assignToSeriesIfUnassigned：文章不存在 → 影響 0 列")
    void assignToSeriesIfUnassigned_unknownArticle_affectsNothing() {
        assertThat(mapper.assignToSeriesIfUnassigned(UUID.randomUUID(), UUID.randomUUID(), 1, now())).isZero();
    }

    @Test
    @DisplayName("removeFromSeriesIfAssigned：屬於該系列 → 影響 1 列，series_uuid／position 清空、version+1")
    void removeFromSeriesIfAssigned_whenAssigned_clearsAndBumpsAudit() {
        UUID series = UUID.randomUUID();
        UUID article = insertArticle(series, 2, "PUBLISHED");
        int versionBefore = versionOf(article);
        LocalDateTime given = LocalDateTime.of(2031, 5, 6, 7, 8, 9);

        int affected = mapper.removeFromSeriesIfAssigned(article, series, given);

        assertThat(affected).isEqualTo(1);
        assertThat(seriesUuidOf(article)).isNull();
        assertThat(positionOf(article)).isNull();
        assertThat(versionOf(article)).isEqualTo(versionBefore + 1);
        assertThat(updatedAtOf(article)).isEqualTo(given);
    }

    @Test
    @DisplayName("removeFromSeriesIfAssigned：屬於別的系列或沒有系列 → 影響 0 列，原歸屬不變；再移出一次也是 0（冪等）")
    void removeFromSeriesIfAssigned_whenNotInThatSeries_changesNothing() {
        UUID series = UUID.randomUUID();
        UUID article = insertArticle(series, 2, "PUBLISHED");
        UUID loner = insertArticle(null, null, "PUBLISHED");

        assertThat(mapper.removeFromSeriesIfAssigned(article, UUID.randomUUID(), now())).isZero();
        assertThat(mapper.removeFromSeriesIfAssigned(loner, series, now())).isZero();
        assertThat(mapper.removeFromSeriesIfAssigned(article, series, now())).isEqualTo(1);
        assertThat(mapper.removeFromSeriesIfAssigned(article, series, now())).isZero();

        assertThat(seriesUuidOf(article)).isNull();
    }

    @Test
    @DisplayName("updateSeriesPositionIfAssigned：屬於該系列 → 只改 position（series_uuid 不變）、version+1；不屬於 → 影響 0 列")
    void updateSeriesPositionIfAssigned_onlyWhenAssignedToThatSeries() {
        UUID series = UUID.randomUUID();
        UUID article = insertArticle(series, 2, "PUBLISHED");
        int versionBefore = versionOf(article);

        assertThat(mapper.updateSeriesPositionIfAssigned(article, UUID.randomUUID(), 8, now())).isZero();
        assertThat(positionOf(article)).isEqualTo(2);

        assertThat(mapper.updateSeriesPositionIfAssigned(article, series, 8, now())).isEqualTo(1);
        assertThat(positionOf(article)).isEqualTo(8);
        assertThat(seriesUuidOf(article)).isEqualTo(series);
        assertThat(versionOf(article)).isEqualTo(versionBefore + 1);
    }

    // ─── 計數與導覽 ───

    @Test
    @DisplayName("countPublishedBySeriesUuids：只算 PUBLISHED，依系列分組；沒有公開文章的系列不出現在結果")
    void countPublishedBySeriesUuids_countsPublishedOnly_omitsZero() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID onlyDraft = UUID.randomUUID();
        insertArticle(a, 1, "PUBLISHED");
        insertArticle(a, 2, "PUBLISHED");
        insertArticle(a, 3, "DRAFT");
        insertArticle(b, 1, "PUBLISHED");
        insertArticle(onlyDraft, 1, "DRAFT");

        Map<UUID, Integer> counts = mapper.countPublishedBySeriesUuids(List.of(a, b, onlyDraft, UUID.randomUUID()))
                .stream().collect(Collectors.toMap(
                        ArticleMapper.SeriesPublishedCountRow::seriesUuid,
                        ArticleMapper.SeriesPublishedCountRow::publishedCount));

        assertThat(counts).isEqualTo(Map.of(a, 2, b, 1));
    }

    @Test
    @DisplayName("findPrev／NextPublishedInSeries：略過非 PUBLISHED，邊界回傳 null，不會跨到別的系列")
    void findPrevAndNextPublishedInSeries_skipNonPublished_andStayInSeries() {
        UUID series = UUID.randomUUID();
        UUID p1 = insertArticle(series, 1, "PUBLISHED");
        insertArticle(series, 2, "DRAFT");
        UUID p3 = insertArticle(series, 3, "PUBLISHED");
        insertArticle(UUID.randomUUID(), 2, "PUBLISHED");

        ArticleNavRef prevOf3 = mapper.findPrevPublishedInSeries(series, 3);
        ArticleNavRef nextOf1 = mapper.findNextPublishedInSeries(series, 1);

        assertThat(prevOf3).isNotNull();
        assertThat(prevOf3.uuid()).isEqualTo(p1);
        assertThat(nextOf1).isNotNull();
        assertThat(nextOf1.uuid()).isEqualTo(p3);
        assertThat(mapper.findPrevPublishedInSeries(series, 1)).isNull();
        assertThat(mapper.findNextPublishedInSeries(series, 3)).isNull();
    }

    // ─── uuid → id 對應 ───

    @Test
    @DisplayName("findIdsByUuids：回傳 id、uuid 與 series_uuid（供列表補系列標題）；無系列者 seriesUuid 為 null")
    void findIdsByUuids_returnsIdUuidAndSeriesUuid() {
        UUID series = UUID.randomUUID();
        UUID member = insertArticle(series, 1, "PUBLISHED");
        UUID loner = insertArticle(null, null, "PUBLISHED");

        List<Article> rows = mapper.findIdsByUuids(List.of(member, loner));

        assertThat(rows).hasSize(2);
        Article memberRow = rows.stream().filter(r -> r.getUuid().equals(member)).findFirst().orElseThrow();
        Article lonerRow = rows.stream().filter(r -> r.getUuid().equals(loner)).findFirst().orElseThrow();
        assertThat(memberRow.getId()).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM articles WHERE uuid = ?", Long.class, member));
        assertThat(memberRow.getSeriesUuid()).isEqualTo(series);
        assertThat(lonerRow.getSeriesUuid()).isNull();
    }
}
