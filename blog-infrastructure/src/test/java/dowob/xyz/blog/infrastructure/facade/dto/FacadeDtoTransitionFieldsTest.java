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
 * <p>設計 §3.1「過渡 API」：owner 的 DTO 補上 UUID 欄位並保留舊建構子（M6-a 起舊建構子不含已移除的
 * authorId／authorUsername），讓他模組既有的建構呼叫與測試 mock 不必同步修改。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("facade DTO 過渡期 UUID 欄位")
class FacadeDtoTransitionFieldsTest {

    @Test
    @DisplayName("ArticleData 舊 4 參數建構子（M6-b 移除 seriesId 參數）→ authorUuid / seriesUuid 為 null，其餘欄位不變")
    void articleData_legacyFourArgConstructor_uuidFieldsAreNull() {
        UUID articleUuid = UUID.randomUUID();

        ArticleData data = new ArticleData(1L, articleUuid, "PUBLISHED", 2);

        assertThat(data.id()).isEqualTo(1L);
        assertThat(data.uuid()).isEqualTo(articleUuid);
        assertThat(data.status()).isEqualTo("PUBLISHED");
        assertThat(data.seriesPosition()).isEqualTo(2);
        assertThat(data.authorUuid()).isNull();
        assertThat(data.seriesUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleData 新 6 參數建構子 → 所有欄位原樣保留")
    void articleData_canonicalConstructor_keepsAllFields() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();

        ArticleData data = new ArticleData(1L, articleUuid, "DRAFT", 2, authorUuid, seriesUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isEqualTo(seriesUuid);
        assertThat(data.seriesPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("ArticleContentData 舊 8 參數建構子 → authorUuid 為 null")
    void articleContentData_legacyConstructor_authorUuidIsNull() {
        ArticleContentData data = new ArticleContentData(
                1L, UUID.randomUUID(), "t", "s", "c", "sum", null, "PUBLISHED");

        assertThat(data.authorUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleContentData 新 9 參數建構子 → authorUuid 原樣保留")
    void articleContentData_canonicalConstructor_keepsAuthorUuid() {
        UUID authorUuid = UUID.randomUUID();

        ArticleContentData data = new ArticleContentData(
                1L, UUID.randomUUID(), "t", "s", "c", "sum", null, "PUBLISHED", authorUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
    }

    @Test
    @DisplayName("ArticleIndexData 舊 10 參數建構子 → authorUuid 為 null")
    void articleIndexData_legacyConstructor_authorUuidIsNull() {
        ArticleIndexData data = new ArticleIndexData(
                UUID.randomUUID(), "t", "s", "sum", "text", "nick",
                LocalDateTime.now(), 1L, 2L, List.of());

        assertThat(data.authorUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleIndexData 新 11 參數建構子 → authorUuid 原樣保留")
    void articleIndexData_canonicalConstructor_keepsAuthorUuid() {
        UUID authorUuid = UUID.randomUUID();

        ArticleIndexData data = new ArticleIndexData(
                UUID.randomUUID(), "t", "s", "sum", "text", "nick",
                LocalDateTime.now(), 1L, 2L, List.of(), authorUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
    }
}
