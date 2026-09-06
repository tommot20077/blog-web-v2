package dowob.xyz.blog.common.api.errorcode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleErrorCode 單元測試
 *
 * <p>驗證所有文章模組錯誤碼均採用 A02xx 命名空間，且訊息非空。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleErrorCode 單元測試")
class ArticleErrorCodeTest {

    @Test
    @DisplayName("ARTICLE_NOT_FOUND 錯誤碼應為 A0201，訊息不為空")
    void articleNotFound_codeIsA0201() {
        assertThat(ArticleErrorCode.ARTICLE_NOT_FOUND.getCode()).isEqualTo("A0201");
        assertThat(ArticleErrorCode.ARTICLE_NOT_FOUND.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("ARTICLE_PUBLISH_FAILED 錯誤碼應為 A0202")
    void articlePublishFailed_codeIsA0202() {
        assertThat(ArticleErrorCode.ARTICLE_PUBLISH_FAILED.getCode()).isEqualTo("A0202");
        assertThat(ArticleErrorCode.ARTICLE_PUBLISH_FAILED.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("ARTICLE_ACCESS_DENIED 錯誤碼應為 A0203")
    void articleAccessDenied_codeIsA0203() {
        assertThat(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getCode()).isEqualTo("A0203");
        assertThat(ArticleErrorCode.ARTICLE_ACCESS_DENIED.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("ARTICLE_STATUS_TRANSITION_INVALID 錯誤碼應為 A0204")
    void articleStatusTransitionInvalid_codeIsA0204() {
        assertThat(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getCode()).isEqualTo("A0204");
        assertThat(ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("CATEGORY_NOT_FOUND 錯誤碼應為 A0205")
    void categoryNotFound_codeIsA0205() {
        assertThat(ArticleErrorCode.CATEGORY_NOT_FOUND.getCode()).isEqualTo("A0205");
        assertThat(ArticleErrorCode.CATEGORY_NOT_FOUND.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("CATEGORY_HAS_ARTICLES 錯誤碼應為 A0206")
    void categoryHasArticles_codeIsA0206() {
        assertThat(ArticleErrorCode.CATEGORY_HAS_ARTICLES.getCode()).isEqualTo("A0206");
        assertThat(ArticleErrorCode.CATEGORY_HAS_ARTICLES.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("CATEGORY_SLUG_DUPLICATE 錯誤碼應為 A0207")
    void categorySlugDuplicate_codeIsA0207() {
        assertThat(ArticleErrorCode.CATEGORY_SLUG_DUPLICATE.getCode()).isEqualTo("A0207");
        assertThat(ArticleErrorCode.CATEGORY_SLUG_DUPLICATE.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("ARTICLE_CONCURRENT_UPDATE 錯誤碼應為 A0208")
    void articleConcurrentUpdate_codeIsA0208() {
        assertThat(ArticleErrorCode.ARTICLE_CONCURRENT_UPDATE.getCode()).isEqualTo("A0208");
        assertThat(ArticleErrorCode.ARTICLE_CONCURRENT_UPDATE.getMessage()).isNotBlank();
    }

    @Test
    @DisplayName("所有 ArticleErrorCode 均實作 IErrorCode 介面，code 與 message 均不為空")
    void allArticleErrorCodes_implementIErrorCode_withNonBlankFields() {
        for (ArticleErrorCode errorCode : ArticleErrorCode.values()) {
            assertThat(errorCode.getCode())
                    .as("ArticleErrorCode.%s 的 code 不應為空", errorCode.name())
                    .isNotBlank();
            assertThat(errorCode.getMessage())
                    .as("ArticleErrorCode.%s 的 message 不應為空", errorCode.name())
                    .isNotBlank();
            assertThat(errorCode.getCode())
                    .as("ArticleErrorCode.%s 的 code 應以 A02 開頭", errorCode.name())
                    .startsWith("A02");
        }
    }
}
