package dowob.xyz.blog.infrastructure.facade.dto;

import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * article facade DTO 不含任何 Long 識別欄位的契約測試（M6 移除 authorId／authorUsername／seriesId，
 * Z1 移除 article 自有主鍵 {@code id}）。
 *
 * <p>他模組已全部改讀 {@code authorUuid}／{@code seriesUuid}／{@code uuid}，owner 直接從 record 移除這些欄位，
 * 不得保留為恆 null 的欄位或過渡用的舊建構子（會成為靜默陷阱）。</p>
 *
 * @author Yuan
 * @version 3.0（Z1：移除 id 與舊建構子）
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
    @DisplayName("ArticleData 不含 id／authorId／seriesId，保留 uuid／authorUuid／seriesUuid／seriesPosition")
    void articleData_hasNoLongIdentifiers() {
        assertThat(components(ArticleData.class))
                .doesNotContain("id", "authorId", "seriesId")
                .contains("uuid", "status", "authorUuid", "seriesUuid", "seriesPosition");
    }

    @Test
    @DisplayName("Z1：ArticleData／ArticleContentData／ArticleIndexData 只有 canonical 建構子，且沒有任何 Long（識別用）參數")
    void articleDtos_haveOnlyCanonicalConstructorWithoutLongParameters() {
        for (Class<?> type : List.of(ArticleData.class, ArticleContentData.class, ArticleIndexData.class)) {
            assertThat(type.getConstructors())
                    .as("%s 的 public 建構子數量（舊的過渡建構子已移除）", type.getSimpleName())
                    .hasSize(1);
            assertThat(type.getConstructors()[0].getParameterTypes())
                    .as("%s 建構子參數不得含 Long", type.getSimpleName())
                    .doesNotContain(Long.class);
        }
    }

    @Test
    @DisplayName("ArticleContentData 不含 id／authorId，保留 uuid／authorUuid")
    void articleContentData_hasNoLongIdentifiers() {
        assertThat(components(ArticleContentData.class))
                .doesNotContain("id", "authorId")
                .contains("uuid", "authorUuid");
    }

    @Test
    @DisplayName("ArticleIndexData 不含 authorId／authorUsername，保留 authorUuid／authorNickname")
    void articleIndexData_hasNoAuthorIdAndNoUsername() {
        assertThat(components(ArticleIndexData.class))
                .doesNotContain("authorId", "authorUsername")
                .contains("articleUuid", "authorUuid", "authorNickname");
    }
}
