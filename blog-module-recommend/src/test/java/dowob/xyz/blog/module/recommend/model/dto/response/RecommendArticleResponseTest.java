package dowob.xyz.blog.module.recommend.model.dto.response;

import dowob.xyz.blog.infrastructure.facade.dto.ArticleSummaryInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecommendArticleResponse} 單元測試（公開作者功能：回應加 authorUuid）
 *
 * @author Yuan
 * @version 1.0
 */
class RecommendArticleResponseTest {

    @Test
    @DisplayName("from 會把 ArticleSummaryInfo 的 authorUuid 原樣帶到回應")
    void from_withAuthorUuid_mapsAuthorUuid() {
        UUID authorUuid = UUID.randomUUID();
        ArticleSummaryInfo info = new ArticleSummaryInfo(UUID.randomUUID(), "標題", "slug", "摘要",
                "作者", authorUuid, List.of("Java"), 5L, 2L, LocalDateTime.of(2026, 1, 1, 0, 0));

        RecommendArticleResponse response = RecommendArticleResponse.from(info);

        assertThat(response.authorUuid()).isEqualTo(authorUuid);
        assertThat(response.authorNickname()).isEqualTo("作者");
    }

    @Test
    @DisplayName("authorUuid 為 null 時回應的 authorUuid 也是 null（相容舊快取）")
    void from_withNullAuthorUuid_keepsNull() {
        ArticleSummaryInfo info = new ArticleSummaryInfo(UUID.randomUUID(), "標題", "slug", "摘要",
                "作者", null, List.of(), 0L, 0L, null);

        assertThat(RecommendArticleResponse.from(info).authorUuid()).isNull();
    }
}
