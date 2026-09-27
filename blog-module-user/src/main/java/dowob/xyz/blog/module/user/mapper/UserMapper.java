package dowob.xyz.blog.module.user.mapper;

import dowob.xyz.blog.infrastructure.config.UUIDTypeHandler;
import dowob.xyz.blog.module.user.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

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
     * 根據角色查詢用戶列表
     *
     * @param role 角色名稱
     * @return 用戶列表
     */
    @Select("SELECT * FROM users WHERE role = #{role}")
    List<User> findByRole(@Param("role") String role);

    /**
     * 模糊搜尋暱稱
     *
     * @param keyword 關鍵字
     * @return 用戶列表
     */
    @Select("SELECT * FROM users WHERE nickname ILIKE CONCAT('%', #{keyword}, '%')")
    List<User> searchByNickname(@Param("keyword") String keyword);

    /**
     * 批次查詢作者投影
     *
     * <p>只撈 {@code id / uuid / nickname} 三欄：原本逐筆 {@code findById} 是
     * {@code SELECT *}，連 {@code password_hash} 都為了顯示一個暱稱被讀進記憶體（PERF-02）。
     * 回傳的 {@link User} 僅這三個欄位有值，<b>不得</b>當作完整實體使用或回存。</p>
     *
     * <p>呼叫端須自行切批（見 {@code BatchedQuery}）；空清單會產生語法非法的 {@code IN ()}。</p>
     *
     * @param ids 使用者主鍵列表，非空且長度不超過 {@code BatchedQuery.BATCH_SIZE}
     * @return 僅 id / uuid / nickname 有值的使用者列表，順序不保證
     */
    @Results(id = "authorInfoMap", value = {
            @Result(property = "id", column = "id"),
            @Result(property = "uuid", column = "uuid", javaType = UUID.class, typeHandler = UUIDTypeHandler.class),
            @Result(property = "nickname", column = "nickname")
    })
    @Select("<script>" +
            "SELECT id, uuid, nickname FROM users " +
            "WHERE id IN " +
            "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</script>")
    List<User> findAuthorInfoByIds(@Param("ids") List<Long> ids);
}
