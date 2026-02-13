package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtService 單元測試
 *
 * <p>
 * 驗證 JWT Token 的生成、解析與驗證行為。
 * 測試案例 3 (version) 在修復 Bug 前為 Red 狀態：
 * {@code generateToken()} 存入 claim key 為 {@code "version"}，
 * 但 {@code getVersionFromToken()} 原本讀取用 {@code "v"}，導致永遠回傳 null。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
class JwtServiceTest {

    /** 受測物件 */
    private JwtService jwtService;

    /** 測試用 userId */
    private static final Long TEST_USER_ID = 42L;

    /** 測試用 role */
    private static final String TEST_ROLE = "USER";

    /** 測試用 token version */
    private static final String TEST_VERSION = "v1";

    /**
     * 每個測試前重新初始化 JwtService，確保金鑰對是乾淨狀態。
     *
     * <p>
     * 因測試環境直接 new 物件而非透過 Spring Container，
     * {@code @Value} 注入的 {@code expiration} 無法自動設定，
     * 故使用 {@link ReflectionTestUtils#setField} 手動注入為 1 小時 (ms)。
     * </p>
     */
    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "expiration", 3600000L);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 3600000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 604800000L);
        jwtService.init();
    }

    /**
     * 驗證：生成 Token 後可正確取回 userId (subject)。
     */
    @Test
    @DisplayName("generateToken → getUserIdFromToken 應回傳原始 userId")
    void generateToken_andExtractUserId_shouldMatch() {
        String token = jwtService.generateToken(TEST_USER_ID, TEST_ROLE, TEST_VERSION);

        String extracted = jwtService.getUserIdFromToken(token);

        assertThat(extracted).isEqualTo(String.valueOf(TEST_USER_ID));
    }

    /**
     * 驗證：生成 Token 後可正確取回 role。
     */
    @Test
    @DisplayName("generateToken → getRoleFromToken 應回傳原始 role")
    void generateToken_andExtractRole_shouldMatch() {
        String token = jwtService.generateToken(TEST_USER_ID, TEST_ROLE, TEST_VERSION);

        Role extracted = jwtService.getRoleFromToken(token);

        assertThat(extracted).isEqualTo(Role.USER);
    }

    /**
     * 驗證：生成 Token 後可正確取回 version。
     *
     * <p>
     * Bug 修復目標：原始程式碼 {@code getVersionFromToken()} 使用 claim key {@code "v"}，
     * 但 {@code generateToken()} 儲存時使用 {@code "version"}，導致永遠回傳 null。
     * 修正 {@code "v"} → {@code "version"} 後此測試轉為 Green。
     * </p>
     */
    @Test
    @DisplayName("generateToken → getVersionFromToken 應回傳原始 version (Bug 修復驗證)")
    void generateToken_andExtractVersion_shouldMatch() {
        String token = jwtService.generateToken(TEST_USER_ID, TEST_ROLE, TEST_VERSION);

        String extracted = jwtService.getVersionFromToken(token);

        assertThat(extracted).isEqualTo(TEST_VERSION);
    }

    /**
     * 驗證：有效 Token 應通過驗證。
     */
    @Test
    @DisplayName("validateToken → 有效 Token 應回傳 true")
    void validateToken_withValidToken_shouldReturnTrue() {
        String token = jwtService.generateToken(TEST_USER_ID, TEST_ROLE, TEST_VERSION);

        boolean result = jwtService.validateToken(token);

        assertThat(result).isTrue();
    }

    /**
     * 驗證：竄改後的 Token 應驗證失敗。
     */
    @Test
    @DisplayName("validateToken → 竄改 Token 應回傳 false")
    void validateToken_withTamperedToken_shouldReturnFalse() {
        String token = jwtService.generateToken(TEST_USER_ID, TEST_ROLE, TEST_VERSION);
        String tampered = token + "tampered";

        boolean result = jwtService.validateToken(tampered);

        assertThat(result).isFalse();
    }

    /**
     * 驗證：generateAccessToken 應生成含 role 與 version claim 的 Access Token，
     * 且 type claim 為 "access"。
     */
    @Test
    @DisplayName("generateAccessToken → 應生成含 role 與 version 的 Access Token")
    void generateAccessToken_shouldProduceTokenWithRoleAndVersion() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 3600000L);

        String token = jwtService.generateAccessToken(TEST_USER_ID, TEST_ROLE, TEST_VERSION);

        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(String.valueOf(TEST_USER_ID));
        assertThat(jwtService.getRoleFromToken(token)).isEqualTo(Role.USER);
        assertThat(jwtService.getVersionFromToken(token)).isEqualTo(TEST_VERSION);
        assertThat(jwtService.getTokenTypeFromToken(token)).isEqualTo("access");
    }

    /**
     * 驗證：generateRefreshToken 應生成僅含 userId (subject) 的 Refresh Token，
     * 且 type claim 為 "refresh"，不含 role 與 version。
     */
    @Test
    @DisplayName("generateRefreshToken → 應生成僅含 userId 的 Refresh Token")
    void generateRefreshToken_shouldProduceTokenWithUserIdOnly() {
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 604800000L);

        String token = jwtService.generateRefreshToken(TEST_USER_ID);

        assertThat(jwtService.getUserIdFromToken(token)).isEqualTo(String.valueOf(TEST_USER_ID));
        assertThat(jwtService.getTokenTypeFromToken(token)).isEqualTo("refresh");
    }

    /**
     * 驗證：對 Access Token 呼叫 getTokenTypeFromToken 應回傳 "access"。
     */
    @Test
    @DisplayName("getTokenType → Access Token 應回傳 'access'")
    void getTokenType_forAccessToken_shouldReturnAccess() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 3600000L);

        String token = jwtService.generateAccessToken(TEST_USER_ID, TEST_ROLE, TEST_VERSION);

        assertThat(jwtService.getTokenTypeFromToken(token)).isEqualTo("access");
    }

    /**
     * 驗證：對 Refresh Token 呼叫 getTokenTypeFromToken 應回傳 "refresh"。
     */
    @Test
    @DisplayName("getTokenType → Refresh Token 應回傳 'refresh'")
    void getTokenType_forRefreshToken_shouldReturnRefresh() {
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 604800000L);

        String token = jwtService.generateRefreshToken(TEST_USER_ID);

        assertThat(jwtService.getTokenTypeFromToken(token)).isEqualTo("refresh");
    }
}
