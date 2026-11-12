package dowob.xyz.blog.module.version.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

/**
 * UserPreference mapper：UPSERT，以及使用者刪帳時的清理（DATA-02）。
 *
 * @author Yuan
 * @version 1.0
 */
@Mapper
public interface UserPreferenceMapper {

    /**
     * UPSERT 一筆 user preference。
     */
    @Update({
        "INSERT INTO user_preferences (user_uuid, pref_key, pref_value)",
        "VALUES (#{userUuid}::uuid, #{prefKey}, #{prefValue})",
        "ON CONFLICT (user_uuid, pref_key) DO UPDATE",
        "  SET pref_value = EXCLUDED.pref_value, updated_at = CURRENT_TIMESTAMP"
    })
    int upsert(@Param("userUuid") UUID userUuid,
               @Param("prefKey") String prefKey,
               @Param("prefValue") String prefValue);

    /**
     * 刪除某位使用者的所有偏好設定（使用者刪除帳號時清理，DATA-02）。天然冪等：已無偏好時刪除 0 筆，不會出錯。
     *
     * <p>單一 SQL 敘述；{@code uq_user_preferences_user_key} (user_uuid, pref_key) 以 {@code user_uuid} 開頭，可走該索引。</p>
     *
     * @param userUuid 使用者公開 UUID
     * @return 刪除的偏好筆數
     */
    @Delete("DELETE FROM user_preferences WHERE user_uuid = #{userUuid}::uuid")
    int deleteByUserUuid(@Param("userUuid") UUID userUuid);
}
