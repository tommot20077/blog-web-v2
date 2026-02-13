package dowob.xyz.blog.infrastructure.facade;

import java.util.Optional;
import java.util.UUID;

/**
 * 用戶模組跨模組查詢 Facade 介面
 *
 * <p>
 * 定義文章等其他模組存取用戶資料的合約。
 * 實作由 blog-module-user 提供，透過 Spring DI 注入。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
public interface UserFacade {

    /**
     * 根據用戶內部 ID 取得對外公開的 UUID
     *
     * @param userId 用戶資料庫主鍵
     * @return 用戶 UUID，若不存在則回傳 empty
     */
    Optional<UUID> getUserUuidById(Long userId);

    /**
     * 根據用戶內部 ID 取得暱稱
     *
     * @param userId 用戶資料庫主鍵
     * @return 用戶暱稱，若不存在則回傳 empty
     */
    Optional<String> getUserNicknameById(Long userId);
}
