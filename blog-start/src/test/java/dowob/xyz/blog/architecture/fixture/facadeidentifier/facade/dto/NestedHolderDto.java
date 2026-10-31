package dowob.xyz.blog.architecture.fixture.facadeidentifier.facade.dto;

import java.util.List;

/**
 * FacadeIdentifierTypeTest 反向驗證 fixture——<b>故意違規</b>：
 * 本身沒有 Long 識別欄位，但巢狀的 {@link Inner} 有。守衛必須檢查所有 facade 套件下的類別，
 * 包含巢狀類別。
 *
 * @param items 巢狀項目
 * @author Yuan
 * @version 1.0
 */
public record NestedHolderDto(List<Inner> items) {

    /**
     * 巢狀項目。
     *
     * @param seriesId 違規：Long 識別
     */
    public record Inner(Long seriesId) {
    }
}
