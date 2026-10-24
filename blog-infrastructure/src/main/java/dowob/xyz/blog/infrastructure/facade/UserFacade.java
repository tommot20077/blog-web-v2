package dowob.xyz.blog.infrastructure.facade;

import dowob.xyz.blog.infrastructure.facade.dto.AuthorInfo;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;

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

    /**
     * 以使用者公開 UUID 批次取得顯示資訊（uuid ＋ nickname ＋ avatarUrl），供其他模組在列表上一次解析整頁的使用者。
     *
     * <p>取代各模組自行 {@code JOIN users}：呼叫端收集一頁內所有使用者 UUID，<b>只呼叫本方法一次</b>。
     * 輸入可含重複 UUID，實作負責去重與切批（{@code BatchedQuery}）；SQL 只 SELECT 顯示所需欄位，
     * 不會把 {@code password_hash} 等欄位讀進記憶體。</p>
     *
     * <p><b>不依使用者狀態或軟刪除過濾</b>：停權、已刪除的使用者仍回傳其暱稱與頭像，
     * 與先前 {@code LEFT JOIN users} 的行為相同。</p>
     *
     * @param userUuids 使用者公開 UUID 集合；{@code null}、空集合或其中的 {@code null} 元素皆被忽略（不查 DB）
     * @return 使用者 UUID → 顯示資訊；查無的 UUID 不出現在結果中，永不回傳 {@code null}
     */
    Map<UUID, UserDisplayInfo> getDisplayInfoByUuids(Collection<UUID> userUuids);
}
