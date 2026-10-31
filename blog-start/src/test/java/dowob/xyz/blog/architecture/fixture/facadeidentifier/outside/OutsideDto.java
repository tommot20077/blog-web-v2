package dowob.xyz.blog.architecture.fixture.facadeidentifier.outside;

import java.util.UUID;

/**
 * FacadeIdentifierTypeTest 可達型別規則的反向驗證 fixture——<b>故意違規</b>：
 * 位於 facade 套件<b>之外</b>（類似 {@code infrastructure.event.TagInfo}、blog-common 的 DTO），
 * 但被 facade 方法直接回傳。
 *
 * @param authorId 違規：Long 識別
 * @param uuid     合規
 * @author Yuan
 * @version 1.0
 */
public record OutsideDto(Long authorId, UUID uuid) {
}
