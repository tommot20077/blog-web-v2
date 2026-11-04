package dowob.xyz.blog.module.user.mapper;

import dowob.xyz.blog.infrastructure.config.UUIDTypeHandler;
import dowob.xyz.blog.module.user.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
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
     * 以公開 UUID 批次查詢使用者顯示投影
     *
     * <p>只撈 {@code uuid / nickname / avatar_url} 三欄，不讀 {@code password_hash} 等欄位。
     * <b>刻意不篩 {@code status}</b>：停權、軟刪除的使用者仍要回傳，
     * 其過往留言才能維持原樣顯示（與先前各模組 {@code LEFT JOIN users} 的行為相同）。
     * 回傳的 {@link User} 僅這三個欄位有值，<b>不得</b>當作完整實體使用或回存。</p>
     *
     * <p>參數明確指定 {@code UUIDTypeHandler}，不依賴全域 {@code type-handlers-package} 的註冊（user 模組的測試設定沒有）。
     * 呼叫端須自行切批（見 {@code BatchedQuery}）；空清單會產生語法非法的 {@code IN ()}。</p>
     *
     * @param uuids 使用者公開 UUID 列表，非空且長度不超過 {@code BatchedQuery.BATCH_SIZE}
     * @return 僅 uuid / nickname / avatarUrl 有值的使用者列表，順序不保證
     */
    @Results(id = "displayInfoMap", value = {
            @Result(property = "uuid", column = "uuid", javaType = UUID.class, typeHandler = UUIDTypeHandler.class),
            @Result(property = "nickname", column = "nickname"),
            @Result(property = "avatarUrl", column = "avatar_url")
    })
    @Select("<script>" +
            "SELECT uuid, nickname, avatar_url FROM users " +
            "WHERE uuid IN " +
            "<foreach collection='uuids' item='uuid' open='(' separator=',' close=')'>" +
            "#{uuid, javaType=java.util.UUID, typeHandler=dowob.xyz.blog.infrastructure.config.UUIDTypeHandler}" +
            "</foreach>" +
            "</script>")
    List<User> findDisplayInfoByUuids(@Param("uuids") List<UUID> uuids);

    /**
     * 在候選作者中篩出 ACTIVE 且暱稱符合樣式者的顯示投影（公開作者搜尋用）
     *
     * <p>只撈 {@code uuid / nickname / avatar_url} 三欄；與 {@link #findDisplayInfoByUuids} 不同，
     * 這裡<b>刻意只留 {@code status = 'ACTIVE'}</b>：公開作者清單不得出現已刪除、停權或未驗證的使用者。
     * 回傳的 {@link User} 僅這三個欄位有值，<b>不得</b>當作完整實體使用或回存。</p>
     *
     * <p>{@code nicknamePattern} 由呼叫端組好 {@code ILIKE} 樣式（前後 {@code %}，內文的 {@code % _ \}
     * 已以反斜線跳脫），本 SQL 明確宣告 {@code ESCAPE '\'}；為 null 時不加暱稱條件。
     * 這是「各批互相獨立的 filter」，屬跨批可合併形狀，呼叫端須經 {@code BatchedQuery} 切批。</p>
     *
     * @param uuids           候選作者公開 UUID，非空且長度不超過 {@code BatchedQuery.BATCH_SIZE}
     * @param nicknamePattern 暱稱 ILIKE 樣式；null 表示不篩暱稱
     * @return 僅 uuid / nickname / avatarUrl 有值的 ACTIVE 使用者，順序不保證
     */
    @ResultMap("displayInfoMap")
    @Select("<script>" +
            "SELECT uuid, nickname, avatar_url FROM users " +
            "WHERE status = 'ACTIVE' " +
            "AND uuid IN " +
            "<foreach collection='uuids' item='uuid' open='(' separator=',' close=')'>" +
            "#{uuid, javaType=java.util.UUID, typeHandler=dowob.xyz.blog.infrastructure.config.UUIDTypeHandler}" +
            "</foreach>" +
            "<if test='nicknamePattern != null'> AND nickname ILIKE #{nicknamePattern} ESCAPE '\\'</if>" +
            "</script>")
    List<User> findActiveAuthorsByUuids(@Param("uuids") List<UUID> uuids,
                                        @Param("nicknamePattern") String nicknamePattern);
}
