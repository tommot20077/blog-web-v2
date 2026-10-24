package dowob.xyz.blog.architecture.fixture.principaltype.inject;

import org.springframework.security.core.annotation.CurrentSecurityContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 反向驗證 fixture——<b>故意違規</b>：被 {@code @CurrentSecurityContext} meta 標註的自訂註解。
 *
 * @author Yuan
 * @version 1.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@CurrentSecurityContext(expression = "authentication.principal")
public @interface CustomSecurityContext {
}
