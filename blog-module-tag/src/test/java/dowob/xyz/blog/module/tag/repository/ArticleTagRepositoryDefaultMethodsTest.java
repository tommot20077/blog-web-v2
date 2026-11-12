package dowob.xyz.blog.module.tag.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

/**
 * {@link ArticleTagRepository} 批次 default 方法的空輸入防護單元測試（PERF-27）。
 *
 * <p>非空路徑的真實 SQL 由 {@code TagFacadeSyncDiffIT}、{@code ArticleTagRepositoryIT} 以真 DB 覆蓋；
 * 本類只鎖定「集合為 null／空時不發查詢、回傳 0」的防護分支。</p>
 */
class ArticleTagRepositoryDefaultMethodsTest {

    private static final UUID ARTICLE_UUID = UUID.fromString("7d1c2a4e-5b3f-4c8a-9e21-0f6b3d8a1c55");

    private final ArticleTagRepository repository =
            mock(ArticleTagRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

    @Test
    @DisplayName("deleteByArticleIdAndTagIds：null 或空集合時回 0 且不發刪除查詢")
    void deleteByArticleIdAndTagIds_nullOrEmpty_returnsZeroWithoutQuery() {
        assertThat(repository.deleteByArticleIdAndTagIds(ARTICLE_UUID, null)).isZero();
        assertThat(repository.deleteByArticleIdAndTagIds(ARTICLE_UUID, List.of())).isZero();

        verify(repository, never()).deleteByArticleIdAndTagIdArray(any(), any());
    }

    @Test
    @DisplayName("saveAll：null 或空清單時回 0 且不發新增查詢")
    void saveAll_nullOrEmpty_returnsZeroWithoutQuery() {
        assertThat(repository.saveAll(ARTICLE_UUID, null)).isZero();
        assertThat(repository.saveAll(ARTICLE_UUID, List.of())).isZero();

        verify(repository, never()).saveAllArray(any(), any());
    }
}
