package dowob.xyz.blog.architecture.fixture.principaltype.inject;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 合規對照 fixture：與登入身分無關的自訂註解，不得被誤判。
 *
 * @author Yuan
 * @version 1.0
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface UnrelatedMarker {
}
