package dowob.xyz.blog.module.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 安全 HTTP(S) URL 驗證註解（SEC-10）
 *
 * <p>只允許 {@code http}／{@code https} 協定的絕對網址，擋下 {@code javascript:}、{@code data:}、
 * {@code vbscript:}、相對路徑與 protocol-relative（{@code //host}）。{@code null} 與空字串視為「清除」，一律通過。
 * 判定邏輯見 {@link SafeHttpUrlValidator}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Documented
@Constraint(validatedBy = SafeHttpUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeHttpUrl {

    /**
     * 驗證失敗訊息
     *
     * @return 訊息
     */
    String message() default "必須是合法的 http/https 網址";

    /**
     * 驗證群組
     *
     * @return 群組
     */
    Class<?>[] groups() default {};

    /**
     * Payload
     *
     * @return payload
     */
    Class<? extends Payload>[] payload() default {};

    /**
     * 長度上限（字元數），應與 DB 欄位長度一致
     *
     * @return 長度上限
     */
    int maxLength() default 2048;

    /**
     * 是否額外允許本站檔案內容路徑（{@code /api/v1/files/{uuid}/content}）
     *
     * <p>檔案上傳 API 回傳的是這種相對路徑，頭貼欄位需要接受它；其餘相對路徑一律拒絕。</p>
     *
     * @return 允許回傳 true
     */
    boolean allowFileContentPath() default false;
}
