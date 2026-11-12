package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「我的文章」與待審列表的排序確定性整合測試（SEC-04 第 3 段）。
 *
 * <p>這兩個列表原本只有 {@code ORDER BY created_at}，沒有 tie-breaker；上限降為 100 後才真正分頁，
 * 同一 {@code created_at} 的多筆資料在 OFFSET 分頁間可能重複或遺漏。本測試讓多篇文章共用同一個
 * {@code created_at}，要求順序固定為 {@code id} 與 created_at 同方向，並逐頁走完驗證無重複無遺漏。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMapper 作者列表／待審列表排序確定性 IT")
class ArticleMapperListOrderingIT extends ArticleSeriesITSupport {

    private static final LocalDateTime SAME_INSTANT = LocalDateTime.of(2026, 11, 4, 10, 0, 0);

    @Autowired private ArticleMapper mapper;

    /** 寫入 count 篇同 author、同 created_at 的文章，回傳依 id 升冪的 id 清單。 */
    private List<Long> insertSameInstant(UUID author, String status, int count) {
        for (int i = 0; i < count; i++) {
            UUID uuid = UUID.randomUUID();
            jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, created_at) "
                            + "VALUES (?, ?, ?, ?, '內容', ?, ?)",
                    uuid, author, "t-" + uuid, "slug-" + uuid, status, Timestamp.valueOf(SAME_INSTANT));
        }
        return jdbc.queryForList("SELECT id FROM articles WHERE author_uuid = ? ORDER BY id ASC", Long.class, author);
    }

    private static List<Long> ids(List<Article> articles) {
        return articles.stream().map(Article::getId).toList();
    }

    @Test
    @DisplayName("findByAuthorUuidPaged：created_at 相同時依 id DESC，逐頁(size=2)走完無重複無遺漏")
    void findByAuthorUuidPaged_sameCreatedAt_orderedByIdDescAcrossPages() {
        UUID author = UUID.randomUUID();
        List<Long> ascIds = insertSameInstant(author, "PUBLISHED", 5);
        List<Long> expected = new ArrayList<>(ascIds);
        java.util.Collections.reverse(expected);

        List<Long> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            collected.addAll(ids(mapper.findByAuthorUuidPaged(author, page * 2L, 2)));
        }

        assertThat(collected).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("findByAuthorUuidAndStatus：created_at 相同時依 id DESC，逐頁走完無重複無遺漏")
    void findByAuthorUuidAndStatus_sameCreatedAt_orderedByIdDescAcrossPages() {
        UUID author = UUID.randomUUID();
        List<Long> ascIds = insertSameInstant(author, "PUBLISHED", 5);
        List<Long> expected = new ArrayList<>(ascIds);
        java.util.Collections.reverse(expected);

        List<Long> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            collected.addAll(ids(mapper.findByAuthorUuidAndStatus(author, ArticleStatus.PUBLISHED, page * 2L, 2)));
        }

        assertThat(collected).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("findPendingReviewPage：created_at 相同時依 id ASC（與 created_at ASC 同向），逐頁走完無重複無遺漏")
    void findPendingReviewPage_sameCreatedAt_orderedByIdAscAcrossPages() {
        UUID author = UUID.randomUUID();
        List<Long> ascIds = insertSameInstant(author, "PENDING_REVIEW", 5);
        // 期望順序（id ASC）恰好等於插入順序，沒有 tie-breaker 時 PostgreSQL 常照 heap 順序回傳而偶然通過。
        // 先 UPDATE 前三筆讓它們的新 tuple 落到 heap 尾端，使 heap 順序（4,5,1,2,3）≠ id 順序。
        jdbc.update("UPDATE articles SET title = title || '' WHERE id IN (?, ?, ?)",
                ascIds.get(0), ascIds.get(1), ascIds.get(2));

        List<Long> collected = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            collected.addAll(ids(mapper.findPendingReviewPage(page * 2L, 2)));
        }

        assertThat(collected).containsExactlyElementsOf(ascIds);
    }
}
