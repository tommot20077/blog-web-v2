package dowob.xyz.blog.common.api.enums;

import dowob.xyz.blog.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UserStatus 單元測試
 *
 * <p>驗證 {@code fromString()} 大小寫不敏感解析與 {@code isAvailable()} 可用性判斷。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserStatus 單元測試")
class UserStatusTest {

    @Nested
    @DisplayName("fromString() 解析測試")
    class FromStringTest {

        @Test
        @DisplayName("完全匹配大寫 'ACTIVE' 應回傳 ACTIVE")
        void fromString_exactMatchActive_returnsActive() {
            assertThat(UserStatus.fromString("ACTIVE")).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("完全匹配 'PENDING_VERIFICATION' 應回傳 PENDING_VERIFICATION")
        void fromString_exactMatchPendingVerification_returnsPendingVerification() {
            assertThat(UserStatus.fromString("PENDING_VERIFICATION")).isEqualTo(UserStatus.PENDING_VERIFICATION);
        }

        @Test
        @DisplayName("完全匹配 'BANNED' 應回傳 BANNED")
        void fromString_exactMatchBanned_returnsBanned() {
            assertThat(UserStatus.fromString("BANNED")).isEqualTo(UserStatus.BANNED);
        }

        @Test
        @DisplayName("完全匹配 'DELETED' 應回傳 DELETED")
        void fromString_exactMatchDeleted_returnsDeleted() {
            assertThat(UserStatus.fromString("DELETED")).isEqualTo(UserStatus.DELETED);
        }

        @Test
        @DisplayName("小寫 'active' 應大小寫不敏感地回傳 ACTIVE")
        void fromString_lowercaseActive_returnsActive() {
            assertThat(UserStatus.fromString("active")).isEqualTo(UserStatus.ACTIVE);
        }

        @Test
        @DisplayName("混合大小寫 'Pending_Verification' 應回傳 PENDING_VERIFICATION")
        void fromString_mixedCasePendingVerification_returnsPendingVerification() {
            assertThat(UserStatus.fromString("Pending_Verification")).isEqualTo(UserStatus.PENDING_VERIFICATION);
        }

        @Test
        @DisplayName("null 輸入應拋出 BusinessException")
        void fromString_null_throwsBusinessException() {
            assertThatThrownBy(() -> UserStatus.fromString(null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("不存在的狀態 'UNKNOWN' 應拋出 BusinessException")
        void fromString_unknownStatus_throwsBusinessException() {
            assertThatThrownBy(() -> UserStatus.fromString("UNKNOWN"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("空字串應拋出 BusinessException")
        void fromString_emptyString_throwsBusinessException() {
            assertThatThrownBy(() -> UserStatus.fromString(""))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("isAvailable() 可用性判斷測試")
    class IsAvailableTest {

        @Test
        @DisplayName("ACTIVE 狀態 isAvailable() 應回傳 true")
        void isAvailable_active_returnsTrue() {
            assertThat(UserStatus.ACTIVE.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("PENDING_VERIFICATION 狀態 isAvailable() 應回傳 true")
        void isAvailable_pendingVerification_returnsTrue() {
            assertThat(UserStatus.PENDING_VERIFICATION.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("BANNED 狀態 isAvailable() 應回傳 false")
        void isAvailable_banned_returnsFalse() {
            assertThat(UserStatus.BANNED.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("DELETED 狀態 isAvailable() 應回傳 false")
        void isAvailable_deleted_returnsFalse() {
            assertThat(UserStatus.DELETED.isAvailable()).isFalse();
        }
    }
}
