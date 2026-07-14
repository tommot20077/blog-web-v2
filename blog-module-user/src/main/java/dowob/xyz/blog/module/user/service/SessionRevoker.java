package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Session 撤銷服務
 *
 * <p>集中管理「撤銷指定用戶所有 session」的 Redis 清理邏輯，供修改密碼、重設密碼、
 * 刪除帳號等需要即時登出所有裝置的場景共用。</p>
 *
 * <p>抽出此類的動機：撤銷 session 需同時清除 auth hash 與 refresh ZSet 兩個鍵，
 * 過去由各呼叫點各自實作而發生漂移（重設密碼未清任何鍵、修改密碼漏清 refresh ZSet），
 * 導致被盜 Token 於改密碼後仍可續用。集中於單一入口可杜絕此類分歧。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
public class SessionRevoker {

    /** Redis 操作模板 */
    private final StringRedisTemplate redisTemplate;

    /**
     * 撤銷指定用戶的所有 session。
     *
     * <p>刪除 Redis 中的 {@code user:auth:{id}} Hash（Token 版本與狀態快取）與
     * refresh ZSet（該用戶所有裝置的 Refresh Token），使既有的 Access Token 與
     * Refresh Token 全數立即失效。下次請求時 {@code JwtAuthenticationFilter} 會以
     * DB 最新版本回填快取，舊 Access Token 因版本不符被拒；被清除的 Refresh Token
     * 也無法再通過 {@code /refresh} 的 ZSet 檢查。</p>
     *
     * @param userId 目標用戶 ID
     */
    public void revokeAllSessions(Long userId) {
        redisTemplate.delete(RedisKeyConstant.getUserAuthKey(userId));
        redisTemplate.delete(RedisKeyConstant.getUserRefreshKey(userId));
    }
}
