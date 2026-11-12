package dowob.xyz.blog.module.reading.mapper;

import dowob.xyz.blog.module.reading.support.ReadingCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BookmarkMapper} 整合測試（真實 Postgres）：收藏列表的排序，以及 {@code created_at} 的寫入。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("BookmarkMapper 整合測試")
class BookmarkMapperIT extends ReadingCleanupITSupport {

    @Autowired private BookmarkMapper mapper;

    @Test
    @DisplayName("findAllMyBookmarkedArticleUuids：created_at 完全相同時，id 較大（較晚寫入）的在前")
    void findAllMyBookmarkedArticleUuids_sameCreatedAt_largerIdFirst() {
        LocalDateTime sameInstant = LocalDateTime.of(2026, 1, 1, 12, 0, 0, 123_456_000);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        for (UUID article : List.of(first, second, third)) {
            jdbc.update("INSERT INTO user_bookmarks (user_uuid, article_uuid, created_at) VALUES (?, ?, ?)",
                    USER_UUID, article, sameInstant);
        }

        List<UUID> result = mapper.findAllMyBookmarkedArticleUuids(USER_UUID);

        assertThat(result).containsExactly(third, second, first);
    }

    @Test
    @DisplayName("findAllMyBookmarkedArticleUuids：created_at 較新的在前，優先於 id")
    void findAllMyBookmarkedArticleUuids_newerCreatedAtBeatsLargerId() {
        UUID newerButSmallerId = UUID.randomUUID();
        UUID olderButLargerId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_bookmarks (user_uuid, article_uuid, created_at) VALUES (?, ?, ?)",
                USER_UUID, newerButSmallerId, LocalDateTime.of(2026, 2, 1, 0, 0));
        jdbc.update("INSERT INTO user_bookmarks (user_uuid, article_uuid, created_at) VALUES (?, ?, ?)",
                USER_UUID, olderButLargerId, LocalDateTime.of(2026, 1, 1, 0, 0));

        assertThat(mapper.findAllMyBookmarkedArticleUuids(USER_UUID))
                .containsExactly(newerButSmallerId, olderButLargerId);
    }

    @Test
    @DisplayName("insertIgnoreConflict：created_at 寫入的是呼叫端傳入的時間（不被 DB 時間取代）")
    void insertIgnoreConflict_storesCallerSuppliedCreatedAt() {
        UUID article = UUID.randomUUID();
        LocalDateTime supplied = LocalDateTime.of(2020, 5, 6, 7, 8, 9);

        int inserted = mapper.insertIgnoreConflict(USER_UUID, article, supplied);

        assertThat(inserted).isEqualTo(1);
        LocalDateTime stored = jdbc.queryForObject(
                "SELECT created_at FROM user_bookmarks WHERE article_uuid = ?", LocalDateTime.class, article);
        assertThat(stored).isEqualTo(supplied);
    }
}
