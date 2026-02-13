package dowob.xyz.blog.module.user.model.dto.response;

/**
 * 登入結果值物件
 *
 * <p>封裝雙 Token 登入的結果，包含短效 Access Token 與長效 Refresh Token。
 * Access Token 直接回傳至前端；Refresh Token 由 Controller 寫入 HttpOnly Cookie。</p>
 *
 * @param accessToken  短效 JWT Access Token（有效期 1 小時）
 * @param refreshToken 長效 JWT Refresh Token（有效期 7 天）
 * @author Yuan
 * @version 1.0
 */
public record LoginResult(String accessToken, String refreshToken) {
}
