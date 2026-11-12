package dowob.xyz.blog.module.article.model.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分類請求的 slug 格式驗證測試
 *
 * <p>分類 slug 原本只有 {@code @NotBlank} / {@code @Size}，管理員可存入大寫、逗號、空白或非 ASCII 字元。
 * 逗號是文章列表多值參數 {@code categorySlug} 的分隔符，含逗號的 slug 永遠無法被篩選；
 * 大寫 slug 則會被前端轉小寫後的請求錯過。故 slug 限定為小寫英數字以單一連字號分隔
 * （Yuan 2026-09-27 決定，選項 C：驗證＋遷移既有資料，見 V23）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("分類請求 slug 格式驗證")
class CategoryRequestValidationTest {

    /** 驗證器工廠（整個測試類共用） */
    private static ValidatorFactory factory;

    /** 驗證器 */
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @ParameterizedTest(name = "合格：{0}")
    @ValueSource(strings = {"backend", "front-end", "c2", "a", "web-3-0"})
    @DisplayName("建立分類：小寫英數字以單一連字號分隔 → 通過")
    void create_conformingSlug_passes(String slug) {
        assertThat(slugViolations(createRequest(slug))).isEmpty();
    }

    @ParameterizedTest(name = "不合格：[{0}]")
    @ValueSource(strings = {"Backend", "a,b", "c++", "前端", "-a", "a-", "a--b", "a b", "a_b"})
    @DisplayName("建立分類：大寫、逗號、符號、非 ASCII、頭尾或連續連字號、空白、底線 → 拒絕")
    void create_nonConformingSlug_rejected(String slug) {
        assertThat(slugViolations(createRequest(slug))).isNotEmpty();
    }

    @Test
    @DisplayName("更新分類：未提供 slug（不修改）→ 通過")
    void update_absentSlug_passes() {
        UpdateCategoryRequest request = new UpdateCategoryRequest();
        request.setName("新名稱");

        assertThat(slugViolations(request)).isEmpty();
    }

    @ParameterizedTest(name = "不合格：[{0}]")
    @ValueSource(strings = {"Backend", "a,b", "前端"})
    @DisplayName("更新分類：提供了不合格的 slug → 拒絕")
    void update_nonConformingSlug_rejected(String slug) {
        UpdateCategoryRequest request = new UpdateCategoryRequest();
        request.setSlug(slug);

        assertThat(slugViolations(request)).isNotEmpty();
    }

    /**
     * 建立其餘欄位皆合法的建立分類請求。
     *
     * @param slug 待驗證的 slug
     * @return 建立分類請求
     */
    private static CreateCategoryRequest createRequest(String slug) {
        CreateCategoryRequest request = new CreateCategoryRequest();
        request.setName("分類");
        request.setSlug(slug);
        return request;
    }

    /**
     * 只取 slug 欄位的違規，避免其他欄位的驗證干擾判斷。
     *
     * @param request 待驗證的請求
     * @param <T>     請求型別
     * @return slug 欄位的違規集合
     */
    private static <T> Set<ConstraintViolation<T>> slugViolations(T request) {
        return validator.validateProperty(request, "slug");
    }
}
