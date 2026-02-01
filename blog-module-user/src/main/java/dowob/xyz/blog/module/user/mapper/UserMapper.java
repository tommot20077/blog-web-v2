package dowob.xyz.blog.module.user.mapper;

import dowob.xyz.blog.module.user.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * User MyBatis Mapper
 * 
 * <p>
 * 用於複雜查詢，簡單 CRUD 仍可使用 Repository
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface UserMapper {

    /**
     * 根據角色查詢用戶列表 (範例)
     *
     * @param role 角色名稱
     * @return 用戶列表
     */
    @Select("SELECT * FROM users WHERE role = #{role}")
    List<User> findByRole(@Param("role") String role);

    /**
     * 模糊搜尋暱稱 (範例)
     *
     * @param keyword 關鍵字
     * @return 用戶列表
     */
    @Select("SELECT * FROM users WHERE nickname ILIKE CONCAT('%', #{keyword}, '%')")
    List<User> searchByNickname(@Param("keyword") String keyword);
}
