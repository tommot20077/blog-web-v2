package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * JWT 工具類 (使用 ECDSA 非對稱加密)
 *
 * <p>支援兩種金鑰初始化模式：</p>
 * <ul>
 *   <li>生產模式：透過 {@code jwt.private-key} 注入 PKCS8 PEM 格式的 EC 私鑰，公鑰由私鑰推導。</li>
 *   <li>開發模式：未設定 {@code jwt.private-key} 時，每次啟動動態生成新的金鑰對。</li>
 * </ul>
 *
 * @author Yuan
 * @version 1.1
 */
@Component
public class JwtService {

    /** EC 私鑰，用於 JWT 簽署 */
    private PrivateKey privateKey;

    /** EC 公鑰，用於 JWT 驗證 */
    private PublicKey publicKey;

    /**
     * PKCS8 PEM 格式的 EC 私鑰。
     * 生產環境透過環境變數 {@code JWT_PRIVATE_KEY} 注入；
     * 本地/開發環境不設定此值（空字串），則走動態生成路徑。
     */
    @Value("${jwt.private-key:}")
    private String privateKeyPem;

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
     * 初始化 EC 金鑰對。
     *
     * <p>
     * 若 {@code jwt.private-key} 已設定（非空），則從 PKCS8 PEM 載入私鑰並推導公鑰（生產模式）；
     * 否則每次啟動動態生成新的 256-bit EC 金鑰對（開發模式）。
     * </p>
     */
    @PostConstruct
    public void init() {
        try {
            if (ObjectUtils.isEmpty(privateKeyPem)) {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
                keyPairGenerator.initialize(256);
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                this.privateKey = keyPair.getPrivate();
                this.publicKey = keyPair.getPublic();
            } else {
                this.privateKey = parsePkcs8PrivateKey(privateKeyPem);
                this.publicKey = derivePublicKey((ECPrivateKey) this.privateKey);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize EC keys", e);
        }
    }

    /**
     * 解析 PKCS8 PEM 格式的 EC 私鑰字串。
     *
     * @param pem PKCS8 PEM 字串（含或不含 header/footer 均可）
     * @return 解析後的 {@link PrivateKey}
     * @throws Exception 解析失敗時拋出
     */
    private PrivateKey parsePkcs8PrivateKey(String pem) throws Exception {
        String stripped = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(stripped);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return kf.generatePrivate(spec);
    }

    /**
     * 由 EC 私鑰推導對應的公鑰。
     *
     * @param ecPrivateKey EC 私鑰
     * @return 對應的 {@link ECPublicKey}
     * @throws Exception 推導失敗時拋出
     */
    private ECPublicKey derivePublicKey(ECPrivateKey ecPrivateKey) throws Exception {
        java.security.spec.ECParameterSpec params = ecPrivateKey.getParams();
        java.security.spec.ECPoint point = params.getGenerator();
        java.math.BigInteger s = ecPrivateKey.getS();
        java.security.spec.ECPoint pubPoint = multiply(s, point, params.getCurve());
        java.security.spec.ECPublicKeySpec pubSpec = new java.security.spec.ECPublicKeySpec(pubPoint, params);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(pubSpec);
    }

    /**
     * EC 純量乘法：計算 k * P（使用 double-and-add 演算法）。
     *
     * @param k     純量
     * @param point 基點
     * @param curve EC 曲線參數
     * @return 結果點
     */
    private java.security.spec.ECPoint multiply(java.math.BigInteger k,
                                                java.security.spec.ECPoint point,
                                                java.security.spec.EllipticCurve curve) {
        java.security.spec.ECPoint result = java.security.spec.ECPoint.POINT_INFINITY;
        java.security.spec.ECPoint addend = point;
        while (k.signum() > 0) {
            if (k.testBit(0)) {
                result = addPoints(result, addend, curve);
            }
            addend = addPoints(addend, addend, curve);
            k = k.shiftRight(1);
        }
        return result;
    }

    /**
     * EC 點加法。
     *
     * @param p1    第一點
     * @param p2    第二點
     * @param curve EC 曲線
     * @return 兩點之和
     */
    private java.security.spec.ECPoint addPoints(java.security.spec.ECPoint p1,
                                                 java.security.spec.ECPoint p2,
                                                 java.security.spec.EllipticCurve curve) {
        if (p1.equals(java.security.spec.ECPoint.POINT_INFINITY)) {
            return p2;
        }
        if (p2.equals(java.security.spec.ECPoint.POINT_INFINITY)) {
            return p1;
        }
        java.math.BigInteger p = ((java.security.spec.ECFieldFp) curve.getField()).getP();
        java.math.BigInteger x1 = p1.getAffineX();
        java.math.BigInteger y1 = p1.getAffineY();
        java.math.BigInteger x2 = p2.getAffineX();
        java.math.BigInteger y2 = p2.getAffineY();

        java.math.BigInteger lambda;
        if (x1.equals(x2)) {
            if (!y1.equals(y2)) {
                return java.security.spec.ECPoint.POINT_INFINITY;
            }
            lambda = x1.pow(2).multiply(java.math.BigInteger.valueOf(3))
                    .add(curve.getA())
                    .multiply(y1.multiply(java.math.BigInteger.TWO).modInverse(p))
                    .mod(p);
        } else {
            lambda = y2.subtract(y1)
                    .multiply(x2.subtract(x1).modInverse(p))
                    .mod(p);
        }
        java.math.BigInteger x3 = lambda.pow(2).subtract(x1).subtract(x2).mod(p);
        java.math.BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(p);
        return new java.security.spec.ECPoint(x3, y3);
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
