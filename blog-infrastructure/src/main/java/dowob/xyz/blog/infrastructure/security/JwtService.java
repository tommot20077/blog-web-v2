package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;

import java.security.spec.*;
import java.util.*;
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

    /** Access Token 過期時間（預設 15 分鐘） */
    @Value("${jwt.access.expiration:900000}")
    private long accessTokenExpiration;

    /** Refresh Token 過期時間（預設 7 天） */
    @Value("${jwt.refresh.expiration:604800000}")
    private long refreshTokenExpiration;

    /**
     * 初始化 EC 金鑰對。
     *
     * <p>
     * 若 {@code jwt.private-key} 已設定（非空），則從 PKCS8 PEM 載入私鑰並推導公鑰；
     * 否則每次啟動動態生成新的 256-bit EC 金鑰對。
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
        ECParameterSpec params = ecPrivateKey.getParams();
        ECPoint point = params.getGenerator();
        BigInteger s = ecPrivateKey.getS();
        ECPoint pubPoint = multiply(s, point, params.getCurve());
        ECPublicKeySpec pubSpec = new ECPublicKeySpec(pubPoint, params);
        KeyFactory kf = KeyFactory.getInstance("EC");
        return (ECPublicKey) kf.generatePublic(pubSpec);
    }

    /**
     * EC 純量乘法：計算 k * P（使用 double-and-add 演算法）。
     *
     * @param k     純量（Scalar），即私鑰的數值
     * @param point 點（Point），即橢圓曲線上的基點
     * @param curve EC 曲線參數
     * @return 計算後的結果點
     */
    private ECPoint multiply(BigInteger k, ECPoint point, EllipticCurve curve) {
        /** 初始化為「無窮遠點」（加法單位元） */
        ECPoint result = ECPoint.POINT_INFINITY;
        ECPoint addend = point;
        while (k.signum() > 0) {
            /** 若當前位元為 1 */
            if (k.testBit(0)) {
                result = addPoints(result, addend, curve);
            }
            /** 每個位元循環都執行倍增 */
            addend = addPoints(addend, addend, curve);
            /** 位元右移，處理下一個位元 */
            k = k.shiftRight(1);
        }
        return result;
    }

    /**
     * EC 點加法。
     *
     * <p>
     * 實作橢圓曲線上的兩點加法幾何運算。包含兩種情況：
     * 1. 兩點不同：計算通過兩點的直線與曲線的第三交點。
     * 2. 兩點相同：計算該點的切線（Point Doubling）。
     * </p>
     *
     * @param p1    第一點
     * @param p2    第二點
     * @param curve EC 曲線
     * @return 兩點之和
     */
    private ECPoint addPoints(ECPoint p1, ECPoint p2, EllipticCurve curve) {
        /** 若其中一點為無窮遠點，結果即為另一點 */
        if (p1.equals(ECPoint.POINT_INFINITY)) {
            return p2;
        }
        if (p2.equals(ECPoint.POINT_INFINITY)) {
            return p1;
        }

        /** 有限體 p */
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        BigInteger x1 = p1.getAffineX();
        BigInteger y1 = p1.getAffineY();
        BigInteger x2 = p2.getAffineX();
        BigInteger y2 = p2.getAffineY();

        BigInteger lambda;
        if (Objects.equals(x1, x2)) {
            /** 兩點相同 (Point Doubling) 或互為負點 */
            if (!Objects.equals(y1, y2)) {
                /** 互為負點，結果為無窮遠 */
                return ECPoint.POINT_INFINITY;
            }
            /** 斜率 lambda = (3 * x1^2 + a) / (2 * y1) mod p */
            lambda = x1.pow(2).multiply(BigInteger.valueOf(3))
                    .add(curve.getA())
                    .multiply(y1.multiply(BigInteger.TWO).modInverse(p))
                    .mod(p);
        } else {
            /** 兩點不同 (Point Addition)，斜率 lambda = (y2 - y1) / (x2 - x1) mod p */
            lambda = y2.subtract(y1)
                    .multiply(x2.subtract(x1).modInverse(p))
                    .mod(p);
        }
        /** 新點座標計算：x3 = lambda^2 - x1 - x2 mod p，y3 = lambda * (x1 - x3) - y1 mod p */
        BigInteger x3 = lambda.pow(2).subtract(x1).subtract(x2).mod(p);
        BigInteger y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(p);
        return new ECPoint(x3, y3);
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
     * <p>
     * 僅包含 type="refresh" claim 與 userId (subject)，不含 role 與 version，
     * 過期時間為 7 天。
     * </p>
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
        long now = System.currentTimeMillis();

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl))
                .signWith(privateKey, Jwts.SIG.ES256)
                .compact();
    }

    /**
     * 驗證 Refresh Token
     *
     * <p>
     * 組合驗證：簽名有效 且 type claim 為 "refresh"。
     * </p>
     *
     * @param token JWT Token
     * @return 若為有效的 Refresh Token 回傳 true，否則 false
     */
    public boolean validateRefreshToken(String token) {
        return validateToken(token) && "refresh".equals(getTokenTypeFromToken(token));
    }

    /**
     * 驗證 JWT Token 簽名與有效期
     *
     * @param token JWT Token 字串
     * @return 若簽名有效且未過期回傳 true，否則 false
     */
    public boolean validateToken(String token) {
        try {
            /** 使用公鑰驗證 */
            Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 從 Token 取得用戶 ID（subject claim）
     *
     * @param token JWT Token 字串
     * @return 用戶 ID 字串
     */
    public String getUserIdFromToken(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * 從 Token 取得版本號（version claim）
     *
     * @param token JWT Token 字串
     * @return Token 版本號字串
     */
    public String getVersionFromToken(String token) {
        return extractClaim(token, claims -> claims.get("version", String.class));
    }

    /**
     * 從 Token 取得角色（role claim）
     *
     * @param token JWT Token 字串
     * @return 對應的 {@link Role} 枚舉值
     */
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
