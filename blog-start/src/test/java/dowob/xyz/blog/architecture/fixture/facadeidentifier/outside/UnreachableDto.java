package dowob.xyz.blog.architecture.fixture.facadeidentifier.outside;

/**
 * FacadeIdentifierTypeTest 反向驗證 fixture：<b>沒有任何 facade 方法觸及</b>，即使含 Long 識別也不該被報
 * （證明守衛是依「可達」而非「整個套件」判定）。
 *
 * @param articleId 不可達，不得被報
 * @author Yuan
 * @version 1.0
 */
public record UnreachableDto(Long articleId) {
}
