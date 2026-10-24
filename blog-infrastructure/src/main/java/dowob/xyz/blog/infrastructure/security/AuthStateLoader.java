package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 使用者認證狀態載入器（Redis 優先，miss 時查 DB 回填）。
 *
 * <p>{@code JwtAuthenticationFilter} 與 {@code /auth/refresh} 共用，確保兩者對「快取沒有資料」
 * 的處理一致（修正 SEC-26：refresh 在 Redis miss 時不再誤判為帳號停權）。</p>
 *
 * <p>快取結構：Hash {@code user:auth:{uuid}}，欄位 {@code id / status / role / version}。
 * <b>四個欄位缺任何一個（含 id）、或內容無法解析，一律視為 miss</b>，改查 DB 並以完整內容覆寫回填；
 * 因此舊格式（不含 id）的 hash 會自動升級。DB 也查不到時回傳 empty，絕不建出缺欄位的 {@link AuthState}。</p>
 *
 * <p>本類只負責「取資料」，不判斷哪些狀態可以通過——該政策留在呼叫端
 * （filter 接受 ACTIVE 與 PENDING_VERIFICATION，refresh 只接受 ACTIVE）。</p>
 *
 * <p><b>已知競態（既有，本類未處理）</b>：撤銷（{@code SessionRevoker} 於 afterCommit 刪除 auth key）
 * 與併發請求之間仍存在 BUG-2026-002 型競態——併發請求若在提交前以「舊的 DB 讀值」開始回填，
 * 回填寫入可能落在 afterCommit 刪 key 之後，使舊版本快取復活。現況與改造前的 filter 行為相同，
 * 根治方案另案處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthStateLoader {

    /** auth hash 的讀取欄位（順序即 multiGet 回傳順序） */
    private static final List<String> HASH_FIELDS = List.of(
            RedisKeyConstant.FIELD_ID,
            RedisKeyConstant.FIELD_STATUS,
            RedisKeyConstant.FIELD_ROLE,
            RedisKeyConstant.FIELD_VERSION);

    /** Redis 操作模板 */
    private final StringRedisTemplate redisTemplate;

    /** 使用者認證服務（DB 回填來源，由 user 模組實作） */
    private final UserAuthService userAuthService;

    /**
     * 載入使用者認證狀態。
     *
     * @param uuid 使用者 UUID
     * @return 認證狀態；Redis 與 DB 都查不到使用者時為 {@link Optional#empty()}
     */
    public Optional<AuthState> load(UUID uuid) {
        String key = RedisKeyConstant.getUserAuthKey(uuid);
        Optional<AuthState> cached = readCache(key, uuid);
        if (cached.isPresent()) {
            return cached;
        }
        return userAuthService.findAuthStateByUuid(uuid).map(state -> {
            writeCache(key, state);
            return state;
        });
    }

    /**
     * 從 Redis 讀取；欄位不齊全或無法解析時視為 miss。
     *
     * @param key  auth hash 的 Redis Key
     * @param uuid 使用者 UUID（hash 本身不存 uuid，由 key 決定）
     * @return 快取中的認證狀態；miss 時為 empty
     */
    private Optional<AuthState> readCache(String key, UUID uuid) {
        List<Object> values = redisTemplate.opsForHash().multiGet(key, List.copyOf(HASH_FIELDS));
        if (values == null || values.size() != HASH_FIELDS.size() || values.stream().anyMatch(v -> v == null)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuthState(
                    Long.valueOf((String) values.get(0)),
                    uuid,
                    UserStatus.valueOf((String) values.get(1)),
                    Role.valueOf((String) values.get(2)),
                    (String) values.get(3)));
        } catch (RuntimeException e) {
            log.warn("User auth cache {} is corrupted, fallback to DB: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 以完整內容回填 Redis（一次寫入四個欄位，避免留下半殘 hash）並設定 TTL。
     *
     * @param key   auth hash 的 Redis Key
     * @param state 來自 DB 的認證狀態
     */
    private void writeCache(String key, AuthState state) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(RedisKeyConstant.FIELD_ID, String.valueOf(state.id()));
        fields.put(RedisKeyConstant.FIELD_STATUS, state.status().name());
        fields.put(RedisKeyConstant.FIELD_ROLE, state.role().name());
        fields.put(RedisKeyConstant.FIELD_VERSION, state.tokenVersion());
        redisTemplate.opsForHash().putAll(key, fields);
        redisTemplate.expire(key, RedisKeyConstant.USER_AUTH_TTL_DAYS, TimeUnit.DAYS);
    }
}
