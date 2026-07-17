package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SessionRevoker 單元測試
 *
 * <p>驗證 session 撤銷的交易語意：於交易進行中呼叫時，Redis 清理必須延遲至交易
 * 提交後（{@code afterCommit}）才執行，避免併發請求在交易提交前把「尚未提交的舊
 * Token 版本」回填進 auth hash，導致撤銷失效。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SessionRevoker 交易語意測試")
class SessionRevokerTest {

    /** Mock：Redis 操作模板 */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** 受測物件 */
    @InjectMocks
    private SessionRevoker sessionRevoker;

    /** 測試用 userId */
    private static final Long USER_ID = 1L;

    /**
     * 確保每個測試後清空交易同步狀態，避免污染其他測試。
     */
    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * 驗證：無交易情境下，應立即刪除 auth hash 與 refresh ZSet 兩個鍵。
     */
    @Test
    @DisplayName("revokeAllSessions → 無交易 → 應立即刪除 auth hash 與 refresh ZSet")
    void revokeAllSessions_withoutTransaction_deletesImmediately() {
        sessionRevoker.revokeAllSessions(USER_ID);

        verify(redisTemplate).delete(RedisKeyConstant.getUserAuthKey(USER_ID));
        verify(redisTemplate).delete(RedisKeyConstant.getUserRefreshKey(USER_ID));
    }

    /**
     * 驗證：交易進行中呼叫時，提交前不得刪除任何 Redis 鍵；必須等到 afterCommit
     * 觸發後才刪除。此為防止「併發請求把未提交的舊 Token 版本回填快取」的核心保證。
     */
    @Test
    @DisplayName("revokeAllSessions → 交易進行中 → 應延遲至 afterCommit 才刪除")
    void revokeAllSessions_withinTransaction_defersUntilAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        sessionRevoker.revokeAllSessions(USER_ID);

        // 交易尚未提交前，絕不可刪除 Redis
        verify(redisTemplate, never()).delete(anyString());

        // 模擬交易提交：觸發已註冊的同步回呼
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).hasSize(1);
        syncs.forEach(TransactionSynchronization::afterCommit);

        verify(redisTemplate).delete(RedisKeyConstant.getUserAuthKey(USER_ID));
        verify(redisTemplate).delete(RedisKeyConstant.getUserRefreshKey(USER_ID));
    }
}
