package dowob.xyz.blog.architecture.fixture.tableaccess.module.reading.mapper;

import org.apache.ibatis.annotations.Select;

/**
 * CrossModuleBoundaryTest 的反向驗證 fixture——<b>故意違規</b>：
 * reading 讀 version 模組的 {@code user_preferences}。舊守衛的業務表範圍只有 articles／comments，
 * 這類表是盲點；證明對照表已擴及所有表。
 *
 * @author Yuan
 * @version 1.0
 */
public interface ReadingVersionTableMapper {

    /** 違規。 */
    @Select("SELECT * FROM user_preferences")
    String readPreferences();
}
