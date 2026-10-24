package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 測試用使用者 uuid（JWT subject） */
    private static final UUID TEST_USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 測試用 role */
    private static final String TEST_ROLE = "USER";

    /** 測試用 token version */
    private static final String TEST_VERSION = "v1";

    /**
     * 每個測試前重新初始化 JwtService，確保金鑰對是乾淨狀態。
     *
     * <p>
     * 因測試環境直接 new 物件而非透過 Spring Container，
     * {@code @Value} 注入的欄位無法自動設定，
     * 故使用 {@link ReflectionTestUtils#setField} 手動注入預設值：
     * accessTokenExpiration = 900000ms（15 分鐘）。
     * </p>
     */
    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "expiration", 3600000L);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 900000L);
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 604800000L);
        jwtService.init();
    }

    /**
     * 驗證：生成 Token 後可正確取回 userId (subject)。
     */
    @Test
    @DisplayName("generateToken → getUserUuidFromToken 應回傳原始 uuid")
    void generateToken_andExtractUserId_shouldMatch() {
        String token = jwtService.generateToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        UUID extracted = jwtService.getUserUuidFromToken(token);

        assertThat(extracted).isEqualTo(TEST_USER_UUID);
    }

    /**
     * 驗證：生成 Token 後可正確取回 role。
     */
    @Test
    @DisplayName("generateToken → getRoleFromToken 應回傳原始 role")
    void generateToken_andExtractRole_shouldMatch() {
        String token = jwtService.generateToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

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
        String token = jwtService.generateToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        String extracted = jwtService.getVersionFromToken(token);

        assertThat(extracted).isEqualTo(TEST_VERSION);
    }

    /**
     * 驗證：有效 Token 應通過驗證。
     */
    @Test
    @DisplayName("validateToken → 有效 Token 應回傳 true")
    void validateToken_withValidToken_shouldReturnTrue() {
        String token = jwtService.generateToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        boolean result = jwtService.validateToken(token);

        assertThat(result).isTrue();
    }

    /**
     * 驗證：竄改後的 Token 應驗證失敗。
     */
    @Test
    @DisplayName("validateToken → 竄改 Token 應回傳 false")
    void validateToken_withTamperedToken_shouldReturnFalse() {
        String token = jwtService.generateToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);
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
        String token = jwtService.generateAccessToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        assertThat(jwtService.getUserUuidFromToken(token)).isEqualTo(TEST_USER_UUID);
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

        String token = jwtService.generateRefreshToken(TEST_USER_UUID);

        assertThat(jwtService.getUserUuidFromToken(token)).isEqualTo(TEST_USER_UUID);
        assertThat(jwtService.getTokenTypeFromToken(token)).isEqualTo("refresh");
    }

    /**
     * 驗證：對 Access Token 呼叫 getTokenTypeFromToken 應回傳 "access"。
     */
    @Test
    @DisplayName("getTokenType → Access Token 應回傳 'access'")
    void getTokenType_forAccessToken_shouldReturnAccess() {
        String token = jwtService.generateAccessToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        assertThat(jwtService.getTokenTypeFromToken(token)).isEqualTo("access");
    }

    /**
     * 驗證：對 Refresh Token 呼叫 getTokenTypeFromToken 應回傳 "refresh"。
     */
    @Test
    @DisplayName("getTokenType → Refresh Token 應回傳 'refresh'")
    void getTokenType_forRefreshToken_shouldReturnRefresh() {
        ReflectionTestUtils.setField(jwtService, "refreshTokenExpiration", 604800000L);

        String token = jwtService.generateRefreshToken(TEST_USER_UUID);

        assertThat(jwtService.getTokenTypeFromToken(token)).isEqualTo("refresh");
    }

    /**
     * 驗證：提供 PKCS8 PEM 格式的 EC 私鑰時，JwtService 應從 PEM 載入金鑰而非動態生成。
     *
     * <p>
     * 預先使用 Java 產生一組 EC 金鑰對，將私鑰轉為 PKCS8 PEM 字串注入，
     * 再驗證用該金鑰生成的 Token 仍可正確解析（公鑰由私鑰推導）。
     * </p>
     */
    @Test
    @DisplayName("提供 PEM 私鑰時，應從 PEM 載入金鑰而非動態生成")
    void whenPrivateKeyPemProvided_shouldLoadFromPem() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        java.security.KeyPair pair = gen.generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";

        JwtService svcWithPem = new JwtService();
        ReflectionTestUtils.setField(svcWithPem, "privateKeyPem", pem);
        ReflectionTestUtils.setField(svcWithPem, "expiration", 3600000L);
        ReflectionTestUtils.setField(svcWithPem, "accessTokenExpiration", 900000L);
        ReflectionTestUtils.setField(svcWithPem, "refreshTokenExpiration", 604800000L);
        svcWithPem.init();

        String token = svcWithPem.generateToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        assertThat(svcWithPem.validateToken(token)).isTrue();
        assertThat(svcWithPem.getUserUuidFromToken(token)).isEqualTo(TEST_USER_UUID);
    }

    /**
     * 驗證：有效的 Refresh Token 應通過 validateRefreshToken 驗證。
     */
    @Test
    @DisplayName("validateRefreshToken → 有效 Refresh Token 應回傳 true")
    void validateRefreshToken_withValidRefreshToken_shouldReturnTrue() {
        String refreshToken = jwtService.generateRefreshToken(TEST_USER_UUID);

        boolean result = jwtService.validateRefreshToken(refreshToken);

        assertThat(result).isTrue();
    }

    /**
     * 驗證：Access Token 傳入 validateRefreshToken 應回傳 false。
     */
    @Test
    @DisplayName("validateRefreshToken → Access Token 應回傳 false")
    void validateRefreshToken_withAccessToken_shouldReturnFalse() {
        String accessToken = jwtService.generateAccessToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        boolean result = jwtService.validateRefreshToken(accessToken);

        assertThat(result).isFalse();
    }

    /**
     * 驗證：竄改後的 Token 傳入 validateRefreshToken 應回傳 false。
     */
    @Test
    @DisplayName("validateRefreshToken → 竄改 Token 應回傳 false")
    void validateRefreshToken_withTamperedToken_shouldReturnFalse() {
        String refreshToken = jwtService.generateRefreshToken(TEST_USER_UUID);
        String tampered = refreshToken + "tampered";

        boolean result = jwtService.validateRefreshToken(tampered);

        assertThat(result).isFalse();
    }

    /**
     * 驗證：未提供 PEM 私鑰時（空字串），JwtService 應動態生成金鑰對（開發模式）。
     */
    @Test
    @DisplayName("未提供 PEM 私鑰時，應動態生成金鑰對（開發模式）")
    void whenPrivateKeyPemEmpty_shouldGenerateDynamically() {
        JwtService svcNoPem = new JwtService();
        ReflectionTestUtils.setField(svcNoPem, "privateKeyPem", "");
        ReflectionTestUtils.setField(svcNoPem, "expiration", 3600000L);
        ReflectionTestUtils.setField(svcNoPem, "accessTokenExpiration", 900000L);
        ReflectionTestUtils.setField(svcNoPem, "refreshTokenExpiration", 604800000L);
        svcNoPem.init();

        String token = svcNoPem.generateToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);

        assertThat(svcNoPem.validateToken(token)).isTrue();
        assertThat(svcNoPem.getUserUuidFromToken(token)).isEqualTo(TEST_USER_UUID);
    }

    /**
     * 驗證：Access Token 預設過期時間應為 15 分鐘（900 秒）。
     *
     * <p>
     * 不額外覆蓋 accessTokenExpiration，直接使用 setUp() 注入的值（反映 @Value 預設值）。
     * 生成的 token 其 expiration claim 應距當下約 900 秒（容差 ±5 秒）。
     * </p>
     *
     * <p>
     * RED 狀態：setUp() 注入 3600000L（1小時），expiry ≈ 3600s，斷言失敗。<br>
     * GREEN 狀態：setUp() 改注入 900000L（15分鐘），expiry ≈ 900s，斷言通過。
     * </p>
     */
    @Test
    @DisplayName("Access token 應在 15 分鐘後過期")
    void generateAccessToken_shouldExpireIn15Minutes() {
        // 不覆蓋 accessTokenExpiration，使用 setUp() 注入的預設值

        long beforeMs = System.currentTimeMillis();
        String token = jwtService.generateAccessToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);
        long afterMs = System.currentTimeMillis();

        Claims claims = ReflectionTestUtils.invokeMethod(jwtService, "extractAllClaims", token);
        assertThat(claims).isNotNull();

        Date expiration = claims.getExpiration();
        long expiryMs = expiration.getTime();

        // expiry 應落在 [before + 895s, after + 905s] 區間內（±5 秒容差）
        assertThat(expiryMs).isBetween(
                beforeMs + 895_000L,
                afterMs  + 905_000L
        );
    }

    /**
     * 驗證：access 與 refresh token 的 subject 必須是使用者 uuid 的字串形式（而非資料庫主鍵）。
     */
    @Test
    @DisplayName("access／refresh token 的 subject 應為使用者 uuid 字串")
    void tokens_subjectShouldBeUserUuidString() {
        String access = jwtService.generateAccessToken(TEST_USER_UUID, TEST_ROLE, TEST_VERSION);
        String refresh = jwtService.generateRefreshToken(TEST_USER_UUID);

        Claims accessClaims = ReflectionTestUtils.invokeMethod(jwtService, "extractAllClaims", access);
        Claims refreshClaims = ReflectionTestUtils.invokeMethod(jwtService, "extractAllClaims", refresh);

        assertThat(accessClaims).isNotNull();
        assertThat(refreshClaims).isNotNull();
        assertThat(accessClaims.getSubject()).isEqualTo(TEST_USER_UUID.toString());
        assertThat(refreshClaims.getSubject()).isEqualTo(TEST_USER_UUID.toString());
    }

    /**
     * 驗證：以本服務金鑰簽署、但 subject 為數字（舊格式）的 access token 一律無效。
     */
    @Test
    @DisplayName("subject 為數字的舊格式 access token 應視為無效")
    void numericSubjectAccessToken_shouldBeInvalid() {
        String legacy = signLegacyToken("42", "access");

        assertThat(jwtService.validateToken(legacy)).isFalse();
    }

    /**
     * 驗證：subject 為數字（舊格式）的 refresh token 一律無效，無法通過 validateRefreshToken。
     */
    @Test
    @DisplayName("subject 為數字的舊格式 refresh token 應視為無效")
    void numericSubjectRefreshToken_shouldBeInvalid() {
        String legacy = signLegacyToken("42", "refresh");

        assertThat(jwtService.validateRefreshToken(legacy)).isFalse();
    }

    /**
     * 驗證：對數字 subject 的 token 取 uuid 時拋出例外，而不是回傳 null 或錯誤值。
     */
    @Test
    @DisplayName("對數字 subject 的 token 呼叫 getUserUuidFromToken 應拋出例外")
    void getUserUuidFromToken_withNumericSubject_shouldThrow() {
        String legacy = signLegacyToken("42", "access");

        assertThatThrownBy(() -> jwtService.getUserUuidFromToken(legacy))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 以本服務的私鑰簽出舊格式（subject 為資料庫主鍵）的 token，模擬切換前已發出的 token。
     *
     * @param subject token 類型為 type claim 之外的 subject 字串
     * @param type    type claim（access／refresh）
     * @return 簽名有效但 subject 非 uuid 的 JWT
     */
    private String signLegacyToken(String subject, String type) {
        java.security.PrivateKey key = (java.security.PrivateKey) ReflectionTestUtils.getField(jwtService, "privateKey");
        return io.jsonwebtoken.Jwts.builder()
                .claim("type", type)
                .claim("role", TEST_ROLE)
                .claim("version", TEST_VERSION)
                .subject(subject)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(key, io.jsonwebtoken.Jwts.SIG.ES256)
                .compact();
    }
}
