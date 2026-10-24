package dowob.xyz.blog.architecture.fixture.principaltype.inject;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 反向驗證 fixture——<b>故意違規</b>：被 {@code @AuthenticationPrincipal} meta 標註的自訂註解，
 * 可繞過規則 (a) 對參數型別的檢查。
 *
 * @author Yuan
 * @version 1.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal
public @interface CustomPrincipal {
}
