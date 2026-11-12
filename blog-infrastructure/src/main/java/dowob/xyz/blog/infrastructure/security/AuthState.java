package dowob.xyz.blog.infrastructure.security;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;

import java.util.Objects;
import java.util.UUID;

/**
 * 使用者認證狀態快照（來自 Redis auth hash 或資料庫）。
 *
 * <p>只描述「現在的事實」：uuid、帳號狀態、角色、Token 版本。
 * 「哪些狀態可以通過驗證」是政策，由呼叫端決定（filter 接受 ACTIVE 與 PENDING_VERIFICATION，
 * refresh 只接受 ACTIVE），不在此類或 {@link AuthStateLoader} 內判斷。</p>
 *
 * <p>四個欄位一律非 null，任一為 null 時建構即拋 {@link NullPointerException}。</p>
 *
 * @param uuid         使用者對外／跨模組識別
 * @param status       帳號狀態
 * @param role         資料庫中的實際角色
 * @param tokenVersion Token 版本號
 * @author Yuan
 * @version 2.0（移除欄位 id）
 */
public record AuthState(UUID uuid, UserStatus status, Role role, String tokenVersion) {

    /**
     * 檢查四個欄位皆非 null。
     *
     * @throws NullPointerException 任一欄位為 null 時
     */
    public AuthState {
        Objects.requireNonNull(uuid, "uuid must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(tokenVersion, "tokenVersion must not be null");
    }
}
