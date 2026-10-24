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
 * <p>三個欄位一律非 null：過渡期間各模組仍依賴 {@link #id()}，因此 {@code id} 在 Z1 移除前皆為非 null。
 * 任一欄位為 null 時建構即拋 {@link NullPointerException}，避免半殘身分流入授權判斷。</p>
 *
 * @param id   使用者主鍵。<b>過渡欄位，收尾階段（Z1）移除，新程式碼一律用 {@link #uuid()}</b>
 * @param uuid 使用者對外／跨模組識別
 * @param role 使用者角色
 * @author Yuan
 * @version 1.0
 */
public record AuthUser(Long id, UUID uuid, Role role) {

    /**
     * 檢查三個欄位皆非 null。
     *
     * @throws NullPointerException 任一欄位為 null 時
     */
    public AuthUser {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(role, "role must not be null");
    }
}
