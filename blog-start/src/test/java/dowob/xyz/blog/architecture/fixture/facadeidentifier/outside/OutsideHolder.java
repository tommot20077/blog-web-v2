package dowob.xyz.blog.architecture.fixture.facadeidentifier.outside;

import java.util.List;

/**
 * FacadeIdentifierTypeTest 反向驗證 fixture：本身合規，但經 {@code List<OutsideChild>} 可達含 Long 識別的 {@link OutsideChild}。
 *
 * @param children 子項目（泛型展開才看得到）
 * @author Yuan
 * @version 1.0
 */
public record OutsideHolder(List<OutsideChild> children) {
}
