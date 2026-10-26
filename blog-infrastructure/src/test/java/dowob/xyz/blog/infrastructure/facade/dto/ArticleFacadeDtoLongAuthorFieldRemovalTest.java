package dowob.xyz.blog.infrastructure.facade.dto;

import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M6-a：article facade DTO 移除 Long 作者識別（{@code authorId}）與登入識別符（{@code authorUsername}）的契約測試。
 *
 * <p>他模組已全部改讀 {@code authorUuid}，owner 直接從 record 移除這些欄位，
 * 不得保留為恆 null 的欄位（會成為靜默陷阱）。{@code ArticleData.id}、{@code seriesId} 是 article 自有主鍵與
 * series 過渡欄位，本階段不動（M6-b／Z1）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("article facade DTO 移除 authorId／authorUsername")
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
    @DisplayName("ArticleData 不含 authorId，保留 id／authorUuid／seriesId")
    void articleData_hasNoAuthorId() {
        assertThat(components(ArticleData.class))
                .doesNotContain("authorId")
                .contains("id", "uuid", "authorUuid", "seriesId", "seriesUuid");
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
