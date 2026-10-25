package dowob.xyz.blog.module.reading.mapper;

import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReadingProgressMapper} 整合測試（真實 Postgres）：{@code ON CONFLICT (user_uuid, article_uuid) DO UPDATE}
 * 對同一組 (user, article) 重複 upsert 時，必須只有一列，且 progress、last_heading_anchor、updated_at
 * 都更新為最後一次的值。
 *
 * <p>進度表沒有指向 articles 的 FK（V27），測試直接以任意 UUID 寫入。</p>
 *
 * @author Yuan
 * @version 1.0
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

    @Test
    @DisplayName("upsert 兩次：只有一列，progress、last_heading_anchor、updated_at 都是第二次的值")
    void upsert_twice_updatesProgressHeadingAndUpdatedAt() {
        UUID article = UUID.randomUUID();

        assertThat(mapper.upsert(USER_UUID, article, new BigDecimal("0.300"), "first")).isEqualTo(1);
        Map<String, Object> first = singleRow(USER_UUID, article);
        assertThat(mapper.upsert(USER_UUID, article, new BigDecimal("0.700"), "second")).isEqualTo(1);
        Map<String, Object> second = singleRow(USER_UUID, article);

        assertThat((BigDecimal) second.get("progress")).isEqualByComparingTo("0.700");
        assertThat(second.get("last_heading_anchor")).isEqualTo("second");
        assertThat(((Timestamp) second.get("updated_at")).toInstant())
                .isAfter(((Timestamp) first.get("updated_at")).toInstant());
    }

    @Test
    @DisplayName("第二次 upsert 的 lastHeading 為 null：last_heading_anchor 也更新為 null，不保留舊值")
    void upsert_secondWithNullHeading_overwritesHeadingWithNull() {
        UUID article = UUID.randomUUID();

        mapper.upsert(USER_UUID, article, new BigDecimal("0.300"), "first");
        mapper.upsert(USER_UUID, article, new BigDecimal("0.400"), null);

        Map<String, Object> row = singleRow(USER_UUID, article);
        assertThat(row.get("last_heading_anchor")).isNull();
        assertThat((BigDecimal) row.get("progress")).isEqualByComparingTo("0.400");
    }

    @Test
    @DisplayName("不同使用者或不同文章各自一列，互不覆蓋")
    void upsert_differentUserOrArticle_keepsSeparateRows() {
        UUID article = UUID.randomUUID();
        UUID otherArticle = UUID.randomUUID();

        mapper.upsert(USER_UUID, article, new BigDecimal("0.100"), "a");
        mapper.upsert(OTHER_USER_UUID, article, new BigDecimal("0.200"), "b");
        mapper.upsert(USER_UUID, otherArticle, new BigDecimal("0.300"), "c");

        assertThat(countProgress(article)).isEqualTo(2);
        assertThat(countProgress(otherArticle)).isEqualTo(1);
        assertThat((BigDecimal) singleRow(USER_UUID, article).get("progress")).isEqualByComparingTo("0.100");
    }
}
