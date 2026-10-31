package dowob.xyz.blog.common.security;

import dowob.xyz.blog.common.api.enums.Role;

import java.util.Objects;
import java.util.UUID;

/**
 * 已登入使用者的身分（Spring Security {@code Authentication} 的 principal）。
 *
 * <p>取代過去直接放入 principal 的 {@code Long userId}。controller 以
 * {@code @AuthenticationPrincipal AuthUser user} 取得；公開端點未登入時為 {@code null}。
 * 讀取 principal 一律經由 {@code SecurityUtils.currentAuthUser(Authentication)}，
 * 不得直接呼叫 {@code Authentication.getPrincipal()}（由 ArchUnit {@code PrincipalTypeTest} 守衛）。</p>
 *
 * <p>兩個欄位一律非 null，任一欄位為 null 時建構即拋 {@link NullPointerException}，
 * 避免半殘身分流入授權判斷。身分只以 {@link #uuid()} 識別；各模組自有的 Long 主鍵不屬於登入身分，
 * 需要時由 owner 模組內部以 uuid 查詢。</p>
 *
 * @param uuid 使用者對外／跨模組識別
 * @param role 使用者角色
 * @author Yuan
 * @version 2.0（移除欄位 id）
 */
public record AuthUser(UUID uuid, Role role) {

    /**
     * 檢查兩個欄位皆非 null。
     *
     * @throws NullPointerException 任一欄位為 null 時
     */
    public AuthUser {
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }
}
