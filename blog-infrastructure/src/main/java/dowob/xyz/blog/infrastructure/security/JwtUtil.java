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
 * @author Yuan4
 * @version 1.0
 */
@Component
public class JwtUtil {

    private PrivateKey privateKey;
    private PublicKey publicKey;

    @Value("${jwt.expiration:86400000}")
    private long expiration; // 1 day in ms

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
     * 生成 Token
     *
     * @param userId  用戶 ID
     * @param role    角色
     * @param version Token 版本號
     * @return JWT Token
     */
    public String generateToken(Long userId, String role, String version) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        claims.put("version", version);
        return createToken(claims, String.valueOf(userId));
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(privateKey, Jwts.SIG.ES256) // 使用私鑰簽名
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
        return extractClaim(token, claims -> claims.get("v", String.class));
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
