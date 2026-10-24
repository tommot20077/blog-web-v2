package dowob.xyz.blog.common.security;

import dowob.xyz.blog.common.api.enums.Role;

import java.util.UUID;

/**
 * 已登入使用者的身分（Spring Security {@code Authentication} 的 principal）。
 *
 * <p>取代過去直接放入 principal 的 {@code Long userId}。controller 以
 * {@code @AuthenticationPrincipal AuthUser user} 取得；公開端點未登入時為 {@code null}。
 * 讀取 principal 一律經由 {@code SecurityUtils.currentAuthUser(Authentication)}，
 * 不得直接呼叫 {@code Authentication.getPrincipal()}（由 ArchUnit {@code PrincipalTypeTest} 守衛）。</p>
 *
 * @param id   使用者主鍵。<b>過渡欄位，收尾階段移除，新程式碼一律用 {@link #uuid()}</b>
 * @param uuid 使用者對外／跨模組識別
 * @param role 使用者角色
 * @author Yuan
 * @version 1.0
 */
public record AuthUser(Long id, UUID uuid, Role role) {
}
