package dowob.xyz.blog.infrastructure.facade.dto;

import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模組 facade DTO 過渡期 UUID 欄位的建構子相容性測試。
 *
 * <p>設計 §3.1「過渡 API」：owner 的 DTO 只增不減地補上 UUID 欄位，並保留舊建構子，
 * 讓他模組既有的建構呼叫與測試 mock 不必同步修改。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("facade DTO 過渡期 UUID 欄位")
class FacadeDtoTransitionFieldsTest {

    @Test
    @DisplayName("ArticleData 舊 6 參數建構子 → 新增的 authorUuid / seriesUuid 為 null，其餘欄位不變")
    void articleData_legacySixArgConstructor_newUuidFieldsAreNull() {
        UUID articleUuid = UUID.randomUUID();

        ArticleData data = new ArticleData(1L, articleUuid, 7L, "PUBLISHED", 3L, 2);

        assertThat(data.id()).isEqualTo(1L);
        assertThat(data.uuid()).isEqualTo(articleUuid);
        assertThat(data.authorId()).isEqualTo(7L);
        assertThat(data.status()).isEqualTo("PUBLISHED");
        assertThat(data.seriesId()).isEqualTo(3L);
        assertThat(data.seriesPosition()).isEqualTo(2);
        assertThat(data.authorUuid()).isNull();
        assertThat(data.seriesUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleData 新 8 參數建構子 → 所有欄位原樣保留")
    void articleData_canonicalConstructor_keepsAllFields() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();

        ArticleData data = new ArticleData(1L, articleUuid, 7L, "DRAFT", 3L, 2, authorUuid, seriesUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isEqualTo(seriesUuid);
        assertThat(data.authorId()).isEqualTo(7L);
        assertThat(data.seriesId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("ArticleContentData 舊 9 參數建構子 → authorUuid 為 null")
    void articleContentData_legacyConstructor_authorUuidIsNull() {
        ArticleContentData data = new ArticleContentData(
                1L, UUID.randomUUID(), 7L, "t", "s", "c", "sum", null, "PUBLISHED");

        assertThat(data.authorId()).isEqualTo(7L);
        assertThat(data.authorUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleContentData 新 10 參數建構子 → authorUuid 原樣保留")
    void articleContentData_canonicalConstructor_keepsAuthorUuid() {
        UUID authorUuid = UUID.randomUUID();

        ArticleContentData data = new ArticleContentData(
                1L, UUID.randomUUID(), 7L, "t", "s", "c", "sum", null, "PUBLISHED", authorUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
    }

    @Test
    @DisplayName("ArticleIndexData 舊 12 參數建構子 → authorUuid 為 null")
    void articleIndexData_legacyConstructor_authorUuidIsNull() {
        ArticleIndexData data = new ArticleIndexData(
                UUID.randomUUID(), "t", "s", "sum", "text", 7L, "user", "nick",
                LocalDateTime.now(), 1L, 2L, List.of());

        assertThat(data.authorId()).isEqualTo(7L);
        assertThat(data.authorUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleIndexData 新 13 參數建構子 → authorUuid 原樣保留")
    void articleIndexData_canonicalConstructor_keepsAuthorUuid() {
        UUID authorUuid = UUID.randomUUID();

        ArticleIndexData data = new ArticleIndexData(
                UUID.randomUUID(), "t", "s", "sum", "text", 7L, "user", "nick",
                LocalDateTime.now(), 1L, 2L, List.of(), authorUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
    }
}
