package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthStateLoader} 單元測試
 *
 * <p>驗證 Redis 命中不查 DB、hash 缺任一必要欄位（含 id）視為 miss 並回填、
 * DB 也查不到時回傳 empty，且 loader 不套用任何狀態政策。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthStateLoader 單元測試")
class AuthStateLoaderTest {

    /** 測試用使用者 uuid */
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 測試用使用者主鍵 */
    private static final Long USER_ID = 7L;

    /** Redis auth hash 的欄位讀取順序（loader 以 multiGet 一次取回） */
    private static final List<Object> FIELDS = List.of(
            RedisKeyConstant.FIELD_ID,
            RedisKeyConstant.FIELD_STATUS,
            RedisKeyConstant.FIELD_ROLE,
            RedisKeyConstant.FIELD_VERSION);

    /** Redis 模板 Mock */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** 使用者認證服務 Mock（DB 回填來源） */
    @Mock
    private UserAuthService userAuthService;

    /** Hash 操作 Mock */
    private HashOperations<String, Object, Object> hashOps;

    /** 受測物件 */
    private AuthStateLoader loader;

    /**
     * 組裝受測物件與 Redis hash Mock。
     */
    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        loader = new AuthStateLoader(redisTemplate, userAuthService);
    }

    @Test
    @DisplayName("Redis 命中（五欄位齊全）→ 直接回傳且不查 DB")
    void load_cacheHit_shouldReturnStateWithoutQueryingDb() {
        when(hashOps.multiGet(RedisKeyConstant.getUserAuthKey(USER_UUID), FIELDS))
                .thenReturn(List.of("7", "ACTIVE", "AUTHOR", "v3"));

        Optional<AuthState> state = loader.load(USER_UUID);

        assertThat(state).contains(new AuthState(USER_ID, USER_UUID, UserStatus.ACTIVE, Role.AUTHOR, "v3"));
        verifyNoInteractions(userAuthService);
        verify(hashOps, never()).putAll(any(), any());
    }

    @Test
    @DisplayName("Redis 命中時狀態原樣回傳，不套用政策（BANNED 也照實回傳）")
    void load_cacheHitWithBannedStatus_shouldReturnAsIs() {
        when(hashOps.multiGet(RedisKeyConstant.getUserAuthKey(USER_UUID), FIELDS))
                .thenReturn(List.of("7", "BANNED", "USER", "v1"));

        Optional<AuthState> state = loader.load(USER_UUID);

        assertThat(state).get().extracting(AuthState::status).isEqualTo(UserStatus.BANNED);
    }

    @Test
    @DisplayName("舊格式 hash 缺 id → 視為 miss，查 DB 並回填含 id 的完整 hash")
    void load_hashMissingId_shouldTreatAsMissAndBackfill() {
        when(hashOps.multiGet(RedisKeyConstant.getUserAuthKey(USER_UUID), FIELDS))
                .thenReturn(Arrays.asList(null, "ACTIVE", "AUTHOR", "v3"));
        AuthState fromDb = new AuthState(USER_ID, USER_UUID, UserStatus.ACTIVE, Role.AUTHOR, "v4");
        when(userAuthService.findAuthStateByUuid(USER_UUID)).thenReturn(Optional.of(fromDb));

        Optional<AuthState> state = loader.load(USER_UUID);

        assertThat(state).contains(fromDb);
        verifyBackfilled(Map.of(
                RedisKeyConstant.FIELD_ID, "7",
                RedisKeyConstant.FIELD_STATUS, "ACTIVE",
                RedisKeyConstant.FIELD_ROLE, "AUTHOR",
                RedisKeyConstant.FIELD_VERSION, "v4"));
    }

    @Test
    @DisplayName("hash 缺 status／role／version 任一欄位 → 皆視為 miss 並查 DB")
    void load_hashMissingAnyRequiredField_shouldTreatAsMiss() {
        List<List<Object>> incompleteRows = List.of(
                Arrays.asList("7", null, "AUTHOR", "v3"),
                Arrays.asList("7", "ACTIVE", null, "v3"),
                Arrays.asList("7", "ACTIVE", "AUTHOR", null));
        AuthState fromDb = new AuthState(USER_ID, USER_UUID, UserStatus.ACTIVE, Role.AUTHOR, "v3");
        when(userAuthService.findAuthStateByUuid(USER_UUID)).thenReturn(Optional.of(fromDb));

        for (List<Object> row : incompleteRows) {
            when(hashOps.multiGet(RedisKeyConstant.getUserAuthKey(USER_UUID), FIELDS)).thenReturn(row);

            assertThat(loader.load(USER_UUID)).contains(fromDb);
        }
        verify(userAuthService, org.mockito.Mockito.times(incompleteRows.size())).findAuthStateByUuid(USER_UUID);
    }

    @Test
    @DisplayName("hash 內容無法解析（id 非數字）→ 視為 miss 並查 DB")
    void load_hashWithCorruptedValue_shouldTreatAsMiss() {
        when(hashOps.multiGet(RedisKeyConstant.getUserAuthKey(USER_UUID), FIELDS))
                .thenReturn(List.of("not-a-number", "ACTIVE", "AUTHOR", "v3"));
        AuthState fromDb = new AuthState(USER_ID, USER_UUID, UserStatus.ACTIVE, Role.AUTHOR, "v3");
        when(userAuthService.findAuthStateByUuid(USER_UUID)).thenReturn(Optional.of(fromDb));

        assertThat(loader.load(USER_UUID)).contains(fromDb);
    }

    @Test
    @DisplayName("Redis miss（整個 hash 不存在）→ 查 DB 回填並設定 7 天 TTL")
    void load_cacheMiss_shouldBackfillWithTtl() {
        when(hashOps.multiGet(RedisKeyConstant.getUserAuthKey(USER_UUID), FIELDS))
                .thenReturn(Arrays.asList(null, null, null, null));
        AuthState fromDb = new AuthState(USER_ID, USER_UUID, UserStatus.PENDING_VERIFICATION, Role.USER, "v1");
        when(userAuthService.findAuthStateByUuid(USER_UUID)).thenReturn(Optional.of(fromDb));

        Optional<AuthState> state = loader.load(USER_UUID);

        assertThat(state).contains(fromDb);
        verify(redisTemplate).expire(RedisKeyConstant.getUserAuthKey(USER_UUID),
                RedisKeyConstant.USER_AUTH_TTL_DAYS, TimeUnit.DAYS);
    }

    @Test
    @DisplayName("Redis miss 且 DB 查不到 → 回傳 empty，且不寫入快取")
    void load_cacheMissAndNotInDb_shouldReturnEmptyWithoutBackfill() {
        when(hashOps.multiGet(RedisKeyConstant.getUserAuthKey(USER_UUID), FIELDS))
                .thenReturn(Arrays.asList(null, null, null, null));
        when(userAuthService.findAuthStateByUuid(USER_UUID)).thenReturn(Optional.empty());

        assertThat(loader.load(USER_UUID)).isEmpty();
        verify(hashOps, never()).putAll(any(), any());
        verify(redisTemplate, never()).expire(any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    @DisplayName("multiGet 回傳 null（Redis pipeline 異常）→ 視為 miss 並查 DB")
    void load_multiGetReturnsNull_shouldTreatAsMiss() {
        when(hashOps.multiGet(eq(RedisKeyConstant.getUserAuthKey(USER_UUID)), anyList())).thenReturn(null);
        when(userAuthService.findAuthStateByUuid(USER_UUID)).thenReturn(Optional.empty());

        assertThat(loader.load(USER_UUID)).isEmpty();
    }

    /**
     * 驗證回填內容：以 putAll 一次寫入四個欄位（避免寫到一半留下半殘 hash）。
     *
     * @param expected 預期寫入的欄位
     */
    @SuppressWarnings("unchecked")
    private void verifyBackfilled(Map<String, String> expected) {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq(RedisKeyConstant.getUserAuthKey(USER_UUID)), captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrderEntriesOf(expected);
    }
}
