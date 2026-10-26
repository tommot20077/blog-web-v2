package dowob.xyz.blog.module.version.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.UUID;

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
        "INSERT INTO user_preferences (user_uuid, pref_key, pref_value)",
        "VALUES (#{userUuid}::uuid, #{prefKey}, #{prefValue})",
        "ON CONFLICT (user_uuid, pref_key) DO UPDATE",
        "  SET pref_value = EXCLUDED.pref_value, updated_at = CURRENT_TIMESTAMP"
    })
    int upsert(@Param("userUuid") UUID userUuid,
               @Param("prefKey") String prefKey,
               @Param("prefValue") String prefValue);
}
