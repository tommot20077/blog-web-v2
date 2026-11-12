package dowob.xyz.blog.module.user.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 社群連結 JSON 驗證註解（SEC-10）
 *
 * <p>value 為「帳號名稱（handle）」而非網址。判定邏輯見 {@link SafeSocialLinksValidator}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Documented
@Constraint(validatedBy = SafeSocialLinksValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface SafeSocialLinks {

    /**
     * 驗證失敗訊息
     *
     * @return 訊息
     */
    String message() default "社群連結格式不正確";

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
}
