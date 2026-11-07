package dowob.xyz.blog.module.article.model.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章內文長度上限驗證（SEC-25 / 決策 A5）
 *
 * <p>建立與更新兩個請求共用同一個上限常數 {@code 200,000}；超過一個字元即違反約束。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章請求內文上限驗證")
class ArticleContentLimitValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("CreateArticleRequest：200,000 字元合格、200,001 字元違反 content 約束")
    void createRequest_boundary() {
        assertThat(validator.validateProperty(create("a".repeat(200_000)), "content")).isEmpty();
        assertThat(validator.validateProperty(create("a".repeat(200_001)), "content")).hasSize(1);
    }

    @Test
    @DisplayName("UpdateArticleRequest：200,000 字元合格、200,001 字元違反 content 約束、null 合格")
    void updateRequest_boundary() {
        assertThat(validator.validateProperty(update("a".repeat(200_000)), "content")).isEmpty();
        assertThat(validator.validateProperty(update("a".repeat(200_001)), "content")).hasSize(1);
        assertThat(validator.validateProperty(update(null), "content")).isEmpty();
    }

    @Test
    @DisplayName("常數只定義一處：ArticleContentLimits.MAX_CONTENT_LENGTH 等於 200,000")
    void constant_isDefinedOnce() {
        assertThat(dowob.xyz.blog.module.article.model.ArticleContentLimits.MAX_CONTENT_LENGTH)
                .isEqualTo(200_000);
    }

    private static CreateArticleRequest create(String content) {
        CreateArticleRequest r = new CreateArticleRequest();
        r.setTitle("t");
        r.setContent(content);
        return r;
    }

    private static UpdateArticleRequest update(String content) {
        UpdateArticleRequest r = new UpdateArticleRequest();
        r.setContent(content);
        return r;
    }
}
