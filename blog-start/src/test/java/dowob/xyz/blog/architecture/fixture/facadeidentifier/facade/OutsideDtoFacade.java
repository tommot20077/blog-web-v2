package dowob.xyz.blog.architecture.fixture.facadeidentifier.facade;

import dowob.xyz.blog.architecture.fixture.facadeidentifier.outside.OutsideDto;
import dowob.xyz.blog.architecture.fixture.facadeidentifier.outside.OutsideHolder;
import dowob.xyz.blog.architecture.fixture.facadeidentifier.outside.OutsideParamDto;

/**
 * FacadeIdentifierTypeTest 可達型別規則的 fixture facade：回傳／接收位於 facade 套件之外的 DTO。
 *
 * @author Yuan
 * @version 1.0
 */
public interface OutsideDtoFacade {

    /** 回傳 facade 套件外、含 Long 識別的 DTO。 */
    OutsideDto fetch();

    /** 回傳本身合規、但經泛型與循環可達違規型別的 DTO。 */
    OutsideHolder fetchHolder();

    /** 接收 facade 套件外、含 Long 識別集合的 DTO。 */
    void accept(OutsideParamDto dto);
}
