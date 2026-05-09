package dowob.xyz.blog.module.version.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * UserPreference UPSERT mapper。
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
        "INSERT INTO user_preferences (user_id, pref_key, pref_value)",
        "VALUES (#{userId}, #{prefKey}, #{prefValue})",
        "ON CONFLICT (user_id, pref_key) DO UPDATE",
        "  SET pref_value = EXCLUDED.pref_value, updated_at = CURRENT_TIMESTAMP"
    })
    int upsert(@Param("userId") Long userId,
               @Param("prefKey") String prefKey,
               @Param("prefValue") String prefValue);
}
