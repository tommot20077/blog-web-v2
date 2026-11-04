package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.UserProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import dowob.xyz.blog.module.user.repository.VerificationTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserService 單元測試
 *
 * <p>
 * 使用 Mockito 隔離所有外部依賴，驗證 UserService 的核心業務邏輯：
 * 更新個人資料、修改密碼與刪除帳號。
 * </p>
 *
 * @author Yuan
 * @version 2.1
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** Mock：密碼加密器 */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Mock：Session 撤銷服務 */
    @Mock
    private SessionRevoker sessionRevoker;

    /** Mock：驗證 Token 存取（刪除帳號時清空該使用者的 token） */
    @Mock
    private VerificationTokenRepository verificationTokenRepository;

    /** Mock：使用者事件發布元件（刪除帳號 commit 後發送 UserDeletedEvent） */
    @Mock
    private UserEventPublisher userEventPublisher;

    /** Mock：交易管理員（搭配真實 TransactionTemplate，以驗證 commit／rollback 與事件發送的先後） */
    @Mock
    private PlatformTransactionManager transactionManager;

    /** Mock：交易狀態 */
    @Mock
    private TransactionStatus transactionStatus;

    /** 受測物件 */
    private UserService userService;

    /**
     * 組裝受測物件。使用真實的 {@link TransactionTemplate}（搭配 Mock 交易管理員），
     * 讓刪除帳號的「交易內寫入、commit 後才發事件」可被驗證。
     */
    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, passwordEncoder, sessionRevoker,
                verificationTokenRepository, new TransactionTemplate(transactionManager), userEventPublisher);
    }

    /** 測試用用戶 ID */
    private static final Long TEST_USER_ID = 1L;

    /** 測試用用戶 UUID（session 撤銷以 uuid 為鍵） */
    private static final java.util.UUID TEST_USER_UUID =
            java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 測試用密碼 */
    private static final String TEST_PASSWORD = "password123";

    /** 測試用暱稱 */
    private static final String TEST_NICKNAME = "testUser";

    /* =========================================================================
       updateProfile 測試
       ========================================================================= */

    /**
     * 驗證：updateProfile 應更新暱稱與個人簡介。
     */
    @Test
    @DisplayName("updateProfile → 應更新暱稱與個人簡介")
    void updateProfile_shouldUpdateNicknameAndBio() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(userRepository.existsByNickname("newNickname")).thenReturn(false);

        userService.updateProfile(TEST_USER_UUID, "newNickname", "新的個人簡介", null, null, null, null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("newNickname");
        assertThat(captor.getValue().getBio()).isEqualTo("新的個人簡介");
    }

    /**
     * 驗證：改名為保留前綴「已刪除使用者」開頭的暱稱 → 拒絕為參數驗證錯誤，不寫入（防假冒已刪除帳號）。
     */
    @Test
    @DisplayName("updateProfile → 暱稱以「已刪除使用者」開頭（含全形空白變體）→ PARAM_VALID_ERROR，不查 DB 不儲存")
    void updateProfile_withReservedDeletedNickname_throwsParamValidError() {
        for (String nickname : new String[]{"已刪除使用者#1a2b3c4d", "已刪除使用者　#1a2b3c4d", "  已刪除使用者"}) {
            assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, nickname, null, null, null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                            .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR.getCode()));
        }

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByUuid(any());
    }

    /**
     * 驗證：updateProfile 應正確設定 website 與 socialLinks 欄位。
     */
    @Test
    @DisplayName("updateProfile → 含 website 與 socialLinks → 應正確儲存欄位")
    void updateProfile_withWebsiteAndSocialLinks_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, "簡介", "https://myblog.com",
                "{\"twitter\":\"@user\"}", "https://cdn.example.com/a.png", "Taipei");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getWebsite()).isEqualTo("https://myblog.com");
        assertThat(captor.getValue().getSocialLinks()).isEqualTo("{\"twitter\":\"@user\"}");
    }

    /**
     * 驗證：updateProfile 應正確設定 avatarUrl 與 location 欄位。
     */
    @Test
    @DisplayName("updateProfile → 含 avatarUrl 與 location → 應正確儲存欄位")
    void updateProfile_withAvatarUrlAndLocation_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, "簡介", null, null,
                "https://cdn.example.com/avatar.png", "Kaohsiung");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");
        assertThat(captor.getValue().getLocation()).isEqualTo("Kaohsiung");
    }

    /* =========================================================================
       updateProfile — SEC-10 防禦縱深（不依賴 controller 的 @Valid）
       ========================================================================= */

    /**
     * 驗證：website 為惡意協定時直接呼叫 service 也應被拒絕，且在碰 DB 之前拒絕。
     */
    @Test
    @DisplayName("updateProfile → website 為 javascript: → 拒絕且不碰 DB（SEC-10）")
    void updateProfile_javascriptWebsite_shouldRejectBeforeAnyDbAccess() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                "javascript:alert(1)", null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：avatarUrl 為 data: 時應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → avatarUrl 為 data: → 拒絕且不儲存（SEC-10）")
    void updateProfile_dataAvatarUrl_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, null, "data:image/svg+xml;base64,PHN2Zz4=", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：avatarUrl 為非檔案路徑的相對 URL 應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → avatarUrl 為 //evil.com → 拒絕（SEC-10）")
    void updateProfile_protocolRelativeAvatarUrl_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, null, "//evil.com/a.png", null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：socialLinks 的 value 含 scheme 時應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → socialLinks value 為 javascript: → 拒絕（SEC-10）")
    void updateProfile_javascriptSocialLink_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, "{\"github\":\"javascript:alert(1)\"}", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：socialLinks 不是合法 JSON object 時應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → socialLinks 不是 JSON object → 拒絕（SEC-10）")
    void updateProfile_malformedSocialLinks_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, "[\"a\"]", null, null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：頭貼為檔案上傳 API 回傳的本站路徑時應成功儲存。
     */
    @Test
    @DisplayName("updateProfile → avatarUrl 為本站檔案內容路徑 → 成功儲存（SEC-10 不破壞頭貼上傳）")
    void updateProfile_fileContentPathAvatar_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        String path = "/api/v1/files/11111111-2222-3333-4444-555555555555/content";

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, null, null, path, null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo(path);
    }

    /**
     * 驗證：前端設定頁實際送出的 socialLinks（E2E 的 myhandle 等 handle 格式）可成功儲存。
     */
    @Test
    @DisplayName("updateProfile → 前端實際 socialLinks（myhandle 等）→ 成功儲存（SEC-10）")
    void updateProfile_frontendSocialLinks_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        String social = "{\"github\":\"myhandle\",\"twitter\":\"mytwitter\",\"linkedin\":\"mylinkedin\"}";

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, null, social, null, null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getSocialLinks()).isEqualTo(social);
    }

    /**
     * 驗證：URL 欄位為空字串（清除）時應通過並原樣儲存。
     */
    @Test
    @DisplayName("updateProfile → URL 欄位為空字串 → 通過（清除）")
    void updateProfile_emptyUrlFields_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, "", "", "", null);

        verify(userRepository).save(any(User.class));
    }

    /* =========================================================================
       updateNotificationPreferences 測試
       ========================================================================= */

    /**
     * 驗證：updateNotificationPreferences 應更新 5 個通知偏好布林欄位。
     */
    @Test
    @DisplayName("updateNotificationPreferences → 應更新 5 個通知偏好欄位")
    void updateNotificationPreferences_shouldUpdateAllFlags() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateNotificationPreferences(TEST_USER_UUID, false, true, false, true, false);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.isNotificationComment()).isFalse();
        assertThat(saved.isNotificationLike()).isTrue();
        assertThat(saved.isNotificationReview()).isFalse();
        assertThat(saved.isNotificationFollow()).isTrue();
        assertThat(saved.isNotificationNewsletter()).isFalse();
    }

    /**
     * 驗證：updateNotificationPreferences 在用戶不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("updateNotificationPreferences → 用戶不存在 → 應拋出 BusinessException")
    void updateNotificationPreferences_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateNotificationPreferences(
                TEST_USER_UUID, true, true, true, true, true))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：updateProfile 使用已存在的暱稱（不屬於自己）應拋出 BusinessException。
     */
    @Test
    @DisplayName("updateProfile → 重複暱稱 → 應拋出 BusinessException")
    void updateProfile_withDuplicateNickname_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(userRepository.existsByNickname("takenNickname")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, "takenNickname", null, null, null, null, null))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：先查通過、寫入（UPDATE）時撞到 nickname 唯一約束（併發改成同一暱稱落敗）→ NICKNAME_DUPLICATED。
     */
    @Test
    @DisplayName("updateProfile → 寫入撞 uq_users_nickname（併發落敗）→ 拋 NICKNAME_DUPLICATED")
    void updateProfile_nicknameUniqueViolationOnSave_throwsNicknameDuplicated() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(buildActiveUser()));
        when(userRepository.existsByNickname("contested")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new org.springframework.dao.DuplicateKeyException(
                "update users failed", new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_users_nickname\"\n"
                        + "  Detail: Key (nickname)=(contested) already exists.", "23505")));

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, "contested", null, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
    }

    /**
     * 驗證：寫入時的其他錯誤（含不認得的約束）不被吞掉，原例外照常拋出。
     */
    @Test
    @DisplayName("updateProfile → 寫入撞到不認得的約束或其他錯誤 → 原例外照常拋出")
    void updateProfile_unknownFailureOnSave_rethrowsOriginal() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(buildActiveUser()));
        org.springframework.dao.DuplicateKeyException unknown = new org.springframework.dao.DuplicateKeyException(
                "update users failed", new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint \"users_email_key\"", "23505"));
        when(userRepository.save(any(User.class))).thenThrow(unknown);

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, null, null, null, null))
                .isSameAs(unknown);
    }

    /**
     * 驗證：updateProfile 在用戶不存在時應拋出 USER_NOT_FOUND BusinessException。
     */
    @Test
    @DisplayName("updateProfile → 用戶不存在 → 應拋出 BusinessException")
    void updateProfile_userNotFound_shouldThrowUserNotFound() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, "newNickname", null, null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /* =========================================================================
       changePassword 測試
       ========================================================================= */

    /**
     * 驗證：changePassword 使用正確舊密碼應更新密碼雜湊。
     */
    @Test
    @DisplayName("changePassword → 正確舊密碼 → 應更新密碼雜湊")
    void changePassword_withCorrectOldPassword_shouldUpdatePasswordHash() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("newEncodedPassword");
    }

    /**
     * 驗證：changePassword 使用錯誤舊密碼應拋出 BusinessException。
     */
    @Test
    @DisplayName("changePassword → 錯誤舊密碼 → 應拋出 BusinessException")
    void changePassword_withWrongOldPassword_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword"))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：changePassword 應遞增 tokenVersion，使舊 JWT Token 失效。
     */
    @Test
    @DisplayName("changePassword → 應遞增 tokenVersion")
    void changePassword_shouldIncrementTokenVersion() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenVersion()).isEqualTo("v2");
    }

    /**
     * 驗證：changePassword 在用戶不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("changePassword → 用戶不存在 → 應拋出 BusinessException")
    void changePassword_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：changePassword 應撤銷該用戶所有 session，使既有 Token 立即失效。
     */
    @Test
    @DisplayName("changePassword → 應撤銷該用戶所有 session")
    void changePassword_shouldRevokeAllSessions() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword");

        verify(sessionRevoker).revokeAllSessions(TEST_USER_UUID);
    }

    /* =========================================================================
       deleteAccount 測試
       ========================================================================= */

    /**
     * 驗證：deleteAccount 使用正確密碼應將帳號狀態設為 DELETED，並把所有個資匿名化（DATA-02）。
     *
     * <p>TEST-01：原測試只斷言 {@code status == DELETED}，把「刪帳只改 status」固化成規格，
     * 使 DATA-02 的匿名化修法被它擋下。依 Q2 定案，正確行為是同時洗掉 users 列上的 PII。</p>
     */
    @Test
    @DisplayName("deleteAccount → 正確密碼 → 狀態設為 DELETED 並匿名化 PII（DATA-02／TEST-01）")
    void deleteAccount_withCorrectPassword_shouldMarkDeletedAndAnonymizePii() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        User saved = captureSavedUser();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(saved.getNickname()).isEqualTo("已刪除使用者#11111111");
        assertThat(saved.getUsername()).isEqualTo("deleted_" + TEST_USER_UUID);
        assertThat(saved.getEmail()).isEqualTo("deleted+" + TEST_USER_UUID + "@invalid.invalid");
        assertThat(saved.getAvatarUrl()).isNull();
        assertThat(saved.getBio()).isNull();
        assertThat(saved.getWebsite()).isNull();
        assertThat(saved.getSocialLinks()).isNull();
        assertThat(saved.getLocation()).isNull();
        assertThat(saved.isEmailVerified()).isFalse();
        assertThat(saved.isNotificationComment()).isFalse();
        assertThat(saved.isNotificationLike()).isFalse();
        assertThat(saved.isNotificationReview()).isFalse();
        assertThat(saved.isNotificationFollow()).isFalse();
        assertThat(saved.isNotificationNewsletter()).isFalse();
    }

    /**
     * 驗證：匿名化後 password_hash 換成「沒有人知道明文」的雜湊——來源是隨機祕密，
     * 不是使用者剛輸入的密碼，也不是原本的雜湊，任何舊登入路徑都不可能再通過。
     */
    @Test
    @DisplayName("deleteAccount → password_hash 換成由隨機祕密產生的雜湊（不是原密碼、不是原雜湊）")
    void deleteAccount_shouldReplacePasswordHashWithUnguessableOne() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);
        when(passwordEncoder.encode(anyString())).thenReturn("unusable-bcrypt-hash");

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        ArgumentCaptor<String> secret = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder).encode(secret.capture());
        assertThat(secret.getValue()).isNotEqualTo(TEST_PASSWORD).hasSizeGreaterThanOrEqualTo(32);
        assertThat(captureSavedUser().getPasswordHash())
                .isEqualTo("unusable-bcrypt-hash")
                .isNotEqualTo("encodedPassword");
    }

    /**
     * 驗證：匿名化同時遞增 tokenVersion（類別文件宣告「修改密碼與刪除帳號會遞增 tokenVersion」），
     * 讓任何殘留的 JWT 即使快取回填也因版本不符被拒。
     */
    @Test
    @DisplayName("deleteAccount → 遞增 tokenVersion")
    void deleteAccount_shouldIncrementTokenVersion() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        assertThat(captureSavedUser().getTokenVersion()).isEqualTo("v2");
    }

    /**
     * 驗證：刪除該使用者所有未用完的驗證 Token（信箱驗證、重設密碼），
     * 避免舊 token 事後把 DELETED 帳號重新啟用或重設密碼。
     */
    @Test
    @DisplayName("deleteAccount → 刪除該使用者的 verification_tokens")
    void deleteAccount_shouldDeleteVerificationTokens() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        verify(verificationTokenRepository).deleteByUserId(TEST_USER_ID);
    }

    /**
     * 驗證：暱稱墓碑值已被他人占用時（例如有人刻意註冊同名卡位），改用完整 uuid 的長格式，
     * 刪帳不得因 uq_users_nickname 撞約束而失敗。
     */
    @Test
    @DisplayName("deleteAccount → 暱稱墓碑值已被占用 → 改用長格式（不撞 uq_users_nickname）")
    void deleteAccount_nicknameTombstoneTaken_shouldFallBackToLongForm() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);
        when(userRepository.existsByNickname("已刪除使用者#11111111")).thenReturn(true);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        assertThat(captureSavedUser().getNickname())
                .isEqualTo("已刪除使用者#" + TEST_USER_UUID.toString().replace("-", ""));
    }

    /**
     * 驗證：username／email 墓碑值被卡位時，改用以隨機 uuid 組成的值，刪帳不得撞 UNIQUE。
     */
    @Test
    @DisplayName("deleteAccount → username／email 墓碑值已被占用 → 改用隨機墓碑值")
    void deleteAccount_usernameAndEmailTombstoneTaken_shouldFallBackToRandomTombstone() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);
        when(userRepository.existsByUsername("deleted_" + TEST_USER_UUID)).thenReturn(true);
        when(userRepository.existsByEmail("deleted+" + TEST_USER_UUID + "@invalid.invalid")).thenReturn(true);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        User saved = captureSavedUser();
        assertThat(saved.getUsername()).startsWith("deleted_").isNotEqualTo("deleted_" + TEST_USER_UUID);
        assertThat(saved.getEmail()).startsWith("deleted+").endsWith("@invalid.invalid")
                .isNotEqualTo("deleted+" + TEST_USER_UUID + "@invalid.invalid");
    }

    /**
     * 驗證：UserDeletedEvent 只在交易 commit 之後才發送（code-standards「Transaction + MQ 時序」）。
     */
    @Test
    @DisplayName("deleteAccount → commit 之後才發送 UserDeletedEvent")
    void deleteAccount_shouldPublishUserDeletedEventAfterCommit() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        InOrder order = inOrder(userRepository, transactionManager, userEventPublisher);
        order.verify(userRepository).save(any(User.class));
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(userEventPublisher).publishDeleted(TEST_USER_UUID);
    }

    /**
     * 驗證：交易失敗（rollback）時不發送事件，也不應有「已刪除」的對外宣告。
     */
    @Test
    @DisplayName("deleteAccount → 交易內寫入失敗（rollback）→ 不發送 UserDeletedEvent")
    void deleteAccount_whenTransactionFails_shouldNotPublishEvent() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        when(userRepository.save(any(User.class))).thenThrow(new IllegalStateException("DB down"));

        assertThatThrownBy(() -> userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(any());
        verifyNoInteractions(userEventPublisher);
        verifyNoInteractions(sessionRevoker);
    }

    /**
     * 驗證：deleteAccount 使用錯誤密碼應拋出 BusinessException。
     */
    @Test
    @DisplayName("deleteAccount → 錯誤密碼 → 應拋出 BusinessException")
    void deleteAccount_withWrongPassword_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
        verifyNoInteractions(verificationTokenRepository, userEventPublisher, sessionRevoker);
    }

    /**
     * 驗證：deleteAccount 在用戶不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("deleteAccount → 用戶不存在 → 應拋出 BusinessException")
    void deleteAccount_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(userEventPublisher);
    }

    /**
     * 驗證（M1）：Redis 撤銷 session 失敗不得吞掉 UserDeletedEvent，也不得讓已 commit 的刪帳回錯誤。
     */
    @Test
    @DisplayName("deleteAccount → session 撤銷（Redis）拋例外 → 事件仍發送、方法正常返回")
    void deleteAccount_whenSessionRevokeFails_shouldStillPublishEventAndReturnNormally() {
        User mockUser = buildPopulatedActiveUser();
        stubCorrectPassword(mockUser);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        doThrow(new IllegalStateException("redis down")).when(sessionRevoker).revokeAllSessions(TEST_USER_UUID);

        assertThatCode(() -> userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD)).doesNotThrowAnyException();

        verify(transactionManager).commit(transactionStatus);
        verify(userEventPublisher).publishDeleted(TEST_USER_UUID);
    }

    /**
     * 驗證：deleteAccount 應撤銷該用戶所有 session（清除 auth hash 與 refresh ZSet），
     * 確保所有 Token 立即失效。
     */
    @Test
    @DisplayName("deleteAccount → 應撤銷該用戶所有 session")
    void deleteAccount_shouldRevokeAllSessions() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        verify(sessionRevoker).revokeAllSessions(TEST_USER_UUID);
    }

    /* =========================================================================
       getUserProfile 測試
       ========================================================================= */

    /**
     * 驗證：getUserProfile 應回傳正確的使用者個人資料。
     */
    @Test
    @DisplayName("getUserProfile → 使用者存在 → 應回傳 UserProfileResponse")
    void getUserProfile_userExists_shouldReturnProfile() {
        UUID testUuid = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2024, 1, 1, 0, 0);
        User mockUser = buildActiveUser();
        mockUser.setUuid(testUuid);
        mockUser.setAvatarUrl("https://example.com/avatar.png");
        mockUser.setLocation("Taipei");
        mockUser.setNotificationComment(false);
        mockUser.setNotificationNewsletter(false);
        mockUser.setEmailVerified(true);
        mockUser.setCreatedAt(createdAt);
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        UserProfileResponse result = userService.getUserProfile(TEST_USER_UUID);

        assertThat(result.uuid()).isEqualTo(testUuid);
        assertThat(result.email()).isEqualTo("test@example.com");
        assertThat(result.nickname()).isEqualTo(TEST_NICKNAME);
        assertThat(result.avatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(result.location()).isEqualTo("Taipei");
        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(result.emailVerified()).isTrue();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.notificationComment()).isFalse();
        assertThat(result.notificationLike()).isTrue();
        assertThat(result.notificationReview()).isTrue();
        assertThat(result.notificationFollow()).isTrue();
        assertThat(result.notificationNewsletter()).isFalse();
    }

    /**
     * 驗證：getUserProfile 在使用者不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("getUserProfile → 使用者不存在 → 應拋出 BusinessException")
    void getUserProfile_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserProfile(TEST_USER_UUID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    /* =========================================================================
       測試輔助方法
       ========================================================================= */

    /**
     * 建立帶有完整個資的 ACTIVE 使用者（供匿名化測試驗證每個欄位都被洗掉）。
     *
     * @return 含 username、頭貼、簡介、網站、社群連結、所在地與通知偏好的使用者
     */
    private User buildPopulatedActiveUser() {
        User user = buildActiveUser();
        user.setUsername("realname");
        user.setAvatarUrl("https://cdn.example.com/avatar.png");
        user.setBio("我是真人");
        user.setWebsite("https://myblog.example.com");
        user.setSocialLinks("{\"twitter\":\"@real\"}");
        user.setLocation("Taipei");
        user.setEmailVerified(true);
        return user;
    }

    /**
     * 讓 findByUuid 回傳指定使用者，且密碼比對通過。
     *
     * @param user 要被「刪除」的使用者
     */
    private void stubCorrectPassword(User user) {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(TEST_PASSWORD, user.getPasswordHash())).thenReturn(true);
    }

    /**
     * 取得 deleteAccount 傳給 {@code userRepository.save} 的使用者。
     *
     * @return 被儲存的使用者
     */
    private User captureSavedUser() {
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        return captor.getValue();
    }

    /**
     * 建立一個 ACTIVE 狀態的測試用 User 物件。
     *
     * @return 已設定基本欄位的 User 實體
     */
    private User buildActiveUser() {
        User user = new User();
        user.setId(TEST_USER_ID);
        user.setUuid(TEST_USER_UUID);
        user.setEmail("test@example.com");
        user.setPasswordHash("encodedPassword");
        user.setNickname(TEST_NICKNAME);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        return user;
    }
}
