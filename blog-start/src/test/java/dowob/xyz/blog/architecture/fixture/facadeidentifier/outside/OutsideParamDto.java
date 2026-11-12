package dowob.xyz.blog.architecture.fixture.facadeidentifier.outside;

import java.util.Collection;

/**
 * FacadeIdentifierTypeTest 反向驗證 fixture——<b>故意違規</b>：facade 方法的<b>參數</b>型別（不是回傳）。
 *
 * @param userIds 違規：Collection&lt;Long&gt;
 * @author Yuan
 * @version 1.0
 */
public record OutsideParamDto(Collection<Long> userIds) {
}
