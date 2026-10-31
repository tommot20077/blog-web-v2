package dowob.xyz.blog.architecture.fixture.facadeidentifier.outside;

/**
 * FacadeIdentifierTypeTest 反向驗證 fixture——<b>故意違規</b>：含 Long 識別，並以 {@code parent} 回指
 * {@link OutsideHolder}（形成循環，驗證遞迴不會無窮迴圈）。
 *
 * @param tagId  違規：Long 識別
 * @param parent 循環參照
 * @author Yuan
 * @version 1.0
 */
public record OutsideChild(Long tagId, OutsideHolder parent) {
}
