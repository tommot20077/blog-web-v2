package dowob.xyz.blog.module.tag.repository;

import dowob.xyz.blog.module.tag.model.UserTagFollow;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * 使用者標籤追蹤資料存取介面
 *
 * <p>
 * 基於 Spring Data JDBC，使用 {@code @Query} 方法操作 {@code user_tag_follows} 中間表，
 * 提供使用者追蹤/取消追蹤標籤及查詢追蹤狀態功能。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface UserTagFollowRepository extends Repository<UserTagFollow, UUID> {

    /**
     * 使用者追蹤標籤（若已追蹤則忽略，避免重複插入衝突）
     *
     * @param userId 使用者 ID
     * @param tagId  標籤 ID
     */
    @Modifying
    @Query("INSERT INTO user_tag_follows (user_id, tag_id) VALUES (:userId, :tagId) ON CONFLICT DO NOTHING")
    void follow(@Param("userId") UUID userId, @Param("tagId") UUID tagId);

    /**
     * 使用者取消追蹤標籤
     *
     * @param userId 使用者 ID
     * @param tagId  標籤 ID
     */
    @Modifying
    @Query("DELETE FROM user_tag_follows WHERE user_id = :userId AND tag_id = :tagId")
    void unfollow(@Param("userId") UUID userId, @Param("tagId") UUID tagId);

    /**
     * 查詢指定使用者對指定標籤的追蹤紀錄數量
     *
     * @param userId 使用者 ID
     * @param tagId  標籤 ID
     * @return 追蹤紀錄數量（0 或 1）
     */
    @Query("SELECT COUNT(*) FROM user_tag_follows WHERE user_id = :userId AND tag_id = :tagId")
    int countByUserIdAndTagId(@Param("userId") UUID userId, @Param("tagId") UUID tagId);
}
