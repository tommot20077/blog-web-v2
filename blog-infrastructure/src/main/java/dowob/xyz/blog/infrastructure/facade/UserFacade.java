package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.AuthorInfo;

import java.util.Collection;
import java.util.Map;
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

    /**
     * 根據用戶內部 ID 取得帳號名稱（username）
     *
     * @param userId 用戶資料庫主鍵
     * @return 用戶帳號，若不存在則回傳 empty
     */
    Optional<String> getUserUsernameById(Long userId);

    /**
     * 批次取得作者投影（uuid ＋ nickname），供文章列表一次解析整頁作者。
     *
     * <p>列表路徑<b>必須</b>用本方法，不得對每筆呼叫 {@link #getUserUuidById} /
     * {@link #getUserNicknameById}——後者一頁 N 篇即 2N 條查詢（PERF-02）。
     * 輸入可含重複 id（一頁文章常出自同一作者），實作負責去重與切批。</p>
     *
     * @param userIds 使用者資料庫主鍵集合；{@code null}、空集合或其中的 {@code null} 元素皆被忽略
     * @return 使用者主鍵 → 作者投影；查無的 id 不出現在結果中，永不回傳 {@code null}
     */
    Map<Long, AuthorInfo> getAuthorInfoByIds(Collection<Long> userIds);
}
