package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 工具類 (使用 ECDSA 非對稱加密)
 *
 * @version 1.0
 */
@Component
public class JwtService {

    private PrivateKey privateKey;
    private PublicKey publicKey;

    /** 舊有 Token 過期時間（預設 24 小時），作為 generateToken() 的預設值使用 */
    @Value("${jwt.expiration:86400000}")
    private long expiration;

    /** Access Token 過期時間（預設 1 小時） */
    @Value("${jwt.access.expiration:3600000}")
    private long accessTokenExpiration;

    /** Refresh Token 過期時間（預設 7 天） */
    @Value("${jwt.refresh.expiration:604800000}")
    private long refreshTokenExpiration;

    /**
     * 初始化 EC 金鑰對
     * <p>
     * 在實際生產環境中，應從 Keystore 或配置加載固定金鑰。
     * 開發階段此處暫時每次啟動重新生成。
     * </p>
     */
    @PostConstruct
    public void init() {
        try {
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
            keyPairGenerator.initialize(256);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();
            this.privateKey = keyPair.getPrivate();
            this.publicKey = keyPair.getPublic();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to initialize EC keys", e);
        }
    }

    /**
     * 生成 Token（向後相容方法，委派至 generateAccessToken）
     *
     * @param userId  用戶 ID
     * @param role    角色
     * @param version Token 版本號
     * @return JWT Access Token
     */
    public String generateToken(Long userId, String role, String version) {
        return generateAccessToken(userId, role, version);
    }

    /**
     * 生成 Access Token
     *
     * <p>包含 role、version、type="access" 三個 claim，過期時間為 1 小時。</p>
     *
     * @param userId  用戶 ID
     * @param role    角色名稱
     * @param version Token 版本號
     * @return 已簽名的 JWT Access Token
     */
    public String generateAccessToken(Long userId, String role, String version) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("version", version);
        claims.put("type", "access");
        return createToken(claims, String.valueOf(userId), accessTokenExpiration);
    }

    /**
     * 生成 Refresh Token
     *
     * <p>僅包含 type="refresh" claim 與 userId (subject)，不含 role 與 version，
     * 過期時間為 7 天。</p>
     *
     * @param userId 用戶 ID
     * @return 已簽名的 JWT Refresh Token
     */
    public String generateRefreshToken(Long userId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "refresh");
        return createToken(claims, String.valueOf(userId), refreshTokenExpiration);
    }

    /**
     * 從 Token 取得 type claim
     *
     * @param token JWT Token
     * @return Token 類型字串，如 "access" 或 "refresh"
     */
    public String getTokenTypeFromToken(String token) {
        return extractClaim(token, c -> c.get("type", String.class));
    }

    private String createToken(Map<String, Object> claims, String subject, long ttl) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + ttl))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    public boolean validateToken(String token) {
        try {
            Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token); // 使用公鑰驗證
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getUserIdFromToken(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public String getVersionFromToken(String token) {
        return extractClaim(token, claims -> claims.get("version", String.class));
    }

    public Role getRoleFromToken(String token) {
        return extractClaim(token, claims -> {
            String roleStr = claims.get("role", String.class);
            return Role.fromRoleName(roleStr);
        });
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
