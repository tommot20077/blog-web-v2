package dowob.xyz.blog.architecture.fixture.tableaccess.module.series.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;

/**
 * CrossModuleBoundaryTest 的反向驗證 fixture——<b>故意違規</b>：
 * series 模組以 {@code @Insert}／{@code @Update}／{@code @Delete} 寫入 user 模組的 {@code users}。
 * 舊守衛只看 {@code @Select}／{@code @Update}，{@code @Insert}／{@code @Delete} 是盲點。
 *
 * @author Yuan
 * @version 1.0
 */
public interface SeriesWriteUsersMapper {

    /** 違規：INSERT INTO users。 */
    @Insert("INSERT INTO users (id) VALUES (#{id})")
    int insertUser();

    /** 違規：UPDATE users。 */
    @Update("UPDATE public.users SET nickname = #{nickname}")
    int updateUser();

    /** 違規：DELETE FROM users。 */
    @Delete("DELETE FROM users WHERE id = #{id}")
    int deleteUser();
}
