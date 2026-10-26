package dowob.xyz.blog.infrastructure.facade.dto;

import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6：article facade DTO 移除 Long 作者識別（{@code authorId}）、登入識別符（{@code authorUsername}）
 * 與 series 的 Long 主鍵（{@code seriesId}）的契約測試。
 *
 * <p>他模組已全部改讀 {@code authorUuid}／{@code seriesUuid}，owner 直接從 record 移除這些欄位，
 * 不得保留為恆 null 的欄位（會成為靜默陷阱）。{@code ArticleData.id} 是 article 自有主鍵，Z1 才移除。</p>
 *
 * @author Yuan
 * @version 2.0（M6-b：移除 seriesId）
 */
@DisplayName("article facade DTO 移除 authorId／authorUsername／seriesId")
class ArticleFacadeDtoLongAuthorFieldRemovalTest {

    /**
     * 取得 record 的元件名稱。
     *
     * @param type record 類別
     * @return 元件名稱清單
     */
    private static List<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    @Test
    @DisplayName("ArticleData 不含 authorId／seriesId，保留 id／uuid／authorUuid／seriesUuid／seriesPosition")
    void articleData_hasNoAuthorIdAndNoSeriesId() {
        assertThat(components(ArticleData.class))
                .doesNotContain("authorId", "seriesId")
                .contains("id", "uuid", "status", "authorUuid", "seriesUuid", "seriesPosition");
    }

    @Test
    @DisplayName("ArticleData 的建構子沒有第二個 Long 參數（不留 series 主鍵的舊建構子形狀）")
    void articleData_constructorsHaveNoLongSeriesParameter() {
        assertThat(ArticleData.class.getConstructors())
                .allSatisfy(constructor -> assertThat(Arrays.stream(constructor.getParameterTypes())
                        .filter(Long.class::equals).count())
                        .as("只有 id 是 Long：%s", constructor)
                        .isEqualTo(1L));
    }

    @Test
    @DisplayName("ArticleContentData 不含 authorId，保留 authorUuid")
    void articleContentData_hasNoAuthorId() {
        assertThat(components(ArticleContentData.class))
                .doesNotContain("authorId")
                .contains("id", "uuid", "authorUuid");
    }

    @Test
    @DisplayName("ArticleIndexData 不含 authorId／authorUsername，保留 authorUuid／authorNickname")
    void articleIndexData_hasNoAuthorIdAndNoUsername() {
        assertThat(components(ArticleIndexData.class))
                .doesNotContain("authorId", "authorUsername")
                .contains("articleUuid", "authorUuid", "authorNickname");
    }
}
