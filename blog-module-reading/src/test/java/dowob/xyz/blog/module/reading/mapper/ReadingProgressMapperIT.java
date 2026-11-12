package dowob.xyz.blog.module.reading.mapper;

import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReadingProgressMapper} 整合測試（真實 Postgres）：{@code ON CONFLICT (user_uuid, article_uuid) DO UPDATE}
 * 對同一組 (user, article) 重複 upsert 時，必須只有一列，且 progress、last_heading_anchor、updated_at
 * 都更新為最後一次的值。
 *
 * <p>單調守衛：upsert 帶入「使用者送出該進度的時間」{@code updatedAt}，衝突時僅在
 * {@code 現有 updated_at <= 傳入 updatedAt} 才覆寫；較舊的寫入不改變列，相等時覆寫。</p>
 *
 * <p>進度表沒有指向 articles 的 FK（V27），測試直接以任意 UUID 寫入。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("ReadingProgressMapper 整合測試")
class ReadingProgressMapperIT extends ReadingCleanupITSupport {

    @Autowired private ReadingProgressMapper mapper;

    private Map<String, Object> singleRow(UUID userUuid, UUID articleUuid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT progress, last_heading_anchor, updated_at FROM user_reading_progress "
                        + "WHERE user_uuid = ? AND article_uuid = ?", userUuid, articleUuid);
        assertThat(rows).as("同一組 (user_uuid, article_uuid) 只能有一列").hasSize(1);
        return rows.get(0);
    }

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 10, 25, 10, 0, 0, 123_000_000);
    private static final LocalDateTime T2 = T1.plusSeconds(30);

    private LocalDateTime updatedAtOf(Map<String, Object> row) {
        return ((Timestamp) row.get("updated_at")).toLocalDateTime();
    }

    @Test
    @DisplayName("upsert 兩次（第二次較新）：只有一列，progress、last_heading_anchor 為第二次的值，updated_at 為傳入的時間而非 DB 時鐘")
    void upsert_twiceWithNewerUpdatedAt_updatesProgressHeadingAndUpdatedAt() {
        UUID article = UUID.randomUUID();

        assertThat(mapper.upsert(USER_UUID, article, new BigDecimal("0.300"), "first", T1)).isEqualTo(1);
        Map<String, Object> first = singleRow(USER_UUID, article);
        assertThat(mapper.upsert(USER_UUID, article, new BigDecimal("0.700"), "second", T2)).isEqualTo(1);
        Map<String, Object> second = singleRow(USER_UUID, article);

        assertThat(updatedAtOf(first)).as("首次寫入的 updated_at 為傳入值").isEqualTo(T1);
        assertThat((BigDecimal) second.get("progress")).isEqualByComparingTo("0.700");
        assertThat(second.get("last_heading_anchor")).isEqualTo("second");
        assertThat(updatedAtOf(second)).isEqualTo(T2);
    }

    @Test
    @DisplayName("較舊 updatedAt 的 upsert：列完全不變（progress、heading、updated_at），影響列數為 0")
    void upsert_withOlderUpdatedAt_leavesRowUnchanged() {
        UUID article = UUID.randomUUID();
        mapper.upsert(USER_UUID, article, new BigDecimal("0.700"), "newer", T2);

        int affected = mapper.upsert(USER_UUID, article, new BigDecimal("0.300"), "older", T1);

        Map<String, Object> row = singleRow(USER_UUID, article);
        assertThat(affected).as("單調守衛擋下舊寫入").isZero();
        assertThat((BigDecimal) row.get("progress")).isEqualByComparingTo("0.700");
        assertThat(row.get("last_heading_anchor")).isEqualTo("newer");
        assertThat(updatedAtOf(row)).isEqualTo(T2);
    }

    @Test
    @DisplayName("相同 updatedAt 的 upsert：允許覆寫（等於時覆寫）")
    void upsert_withEqualUpdatedAt_overwrites() {
        UUID article = UUID.randomUUID();
        mapper.upsert(USER_UUID, article, new BigDecimal("0.300"), "first", T1);

        int affected = mapper.upsert(USER_UUID, article, new BigDecimal("0.600"), "second", T1);

        Map<String, Object> row = singleRow(USER_UUID, article);
        assertThat(affected).isEqualTo(1);
        assertThat((BigDecimal) row.get("progress")).isEqualByComparingTo("0.600");
        assertThat(row.get("last_heading_anchor")).isEqualTo("second");
        assertThat(updatedAtOf(row)).isEqualTo(T1);
    }

    @Test
    @DisplayName("單調依時間而非進度大小：較新的 updatedAt 可把 progress 從高降回低")
    void upsert_newerUpdatedAtWithLowerProgress_overwrites() {
        UUID article = UUID.randomUUID();
        mapper.upsert(USER_UUID, article, new BigDecimal("0.970"), "end", T1);

        mapper.upsert(USER_UUID, article, new BigDecimal("0.200"), "top", T2);

        Map<String, Object> row = singleRow(USER_UUID, article);
        assertThat((BigDecimal) row.get("progress")).isEqualByComparingTo("0.200");
        assertThat(updatedAtOf(row)).isEqualTo(T2);
    }

    @Test
    @DisplayName("第二次 upsert 的 lastHeading 為 null：last_heading_anchor 也更新為 null，不保留舊值")
    void upsert_secondWithNullHeading_overwritesHeadingWithNull() {
        UUID article = UUID.randomUUID();

        mapper.upsert(USER_UUID, article, new BigDecimal("0.300"), "first", T1);
        mapper.upsert(USER_UUID, article, new BigDecimal("0.400"), null, T2);

        Map<String, Object> row = singleRow(USER_UUID, article);
        assertThat(row.get("last_heading_anchor")).isNull();
        assertThat((BigDecimal) row.get("progress")).isEqualByComparingTo("0.400");
    }

    @Test
    @DisplayName("不同使用者或不同文章各自一列，互不覆蓋")
    void upsert_differentUserOrArticle_keepsSeparateRows() {
        UUID article = UUID.randomUUID();
        UUID otherArticle = UUID.randomUUID();

        mapper.upsert(USER_UUID, article, new BigDecimal("0.100"), "a", T1);
        mapper.upsert(OTHER_USER_UUID, article, new BigDecimal("0.200"), "b", T1);
        mapper.upsert(USER_UUID, otherArticle, new BigDecimal("0.300"), "c", T1);

        assertThat(countProgress(article)).isEqualTo(2);
        assertThat(countProgress(otherArticle)).isEqualTo(1);
        assertThat((BigDecimal) singleRow(USER_UUID, article).get("progress")).isEqualByComparingTo("0.100");
    }
}
