package dowob.xyz.blog.infrastructure.facade.dto;

import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模組 facade DTO 的 UUID 欄位建構測試。
 *
 * <p>Z1 已移除過渡期的舊建構子（產生者恆為 null 的 4／8／10 參數建構子），DTO 只剩 canonical 建構子，
 * 本測試確認 UUID 欄位原樣保留。</p>
 *
 * @author Yuan
 * @version 2.0（Z1：移除舊建構子相容性測試）
 */
@DisplayName("facade DTO UUID 欄位")
class FacadeDtoTransitionFieldsTest {

    @Test
    @DisplayName("ArticleData 建構子（5 個元件）→ 所有欄位原樣保留")
    void articleData_canonicalConstructor_keepsAllFields() {
        UUID articleUuid = UUID.randomUUID();
        UUID authorUuid = UUID.randomUUID();
        UUID seriesUuid = UUID.randomUUID();

        ArticleData data = new ArticleData(articleUuid, "DRAFT", 2, authorUuid, seriesUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
        assertThat(data.seriesUuid()).isEqualTo(seriesUuid);
        assertThat(data.seriesPosition()).isEqualTo(2);
    }

    @Test
    @DisplayName("ArticleContentData 建構子（8 個元件）→ authorUuid 原樣保留")
    void articleContentData_canonicalConstructor_keepsAuthorUuid() {
        UUID authorUuid = UUID.randomUUID();

        ArticleContentData data = new ArticleContentData(
                UUID.randomUUID(), "t", "s", "c", "sum", null, "PUBLISHED", authorUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
    }

    @Test
    @DisplayName("ArticleIndexData 建構子（11 個元件）→ authorUuid 原樣保留")
    void articleIndexData_canonicalConstructor_keepsAuthorUuid() {
        UUID authorUuid = UUID.randomUUID();

        ArticleIndexData data = new ArticleIndexData(
                UUID.randomUUID(), "t", "s", "sum", "text", "nick",
                LocalDateTime.now(), 1L, 2L, List.of(), authorUuid);

        assertThat(data.authorUuid()).isEqualTo(authorUuid);
    }
}
