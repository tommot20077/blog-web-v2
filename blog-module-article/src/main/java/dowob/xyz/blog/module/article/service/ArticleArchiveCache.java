package dowob.xyz.blog.module.article.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.article.model.dto.response.ArticleArchiveResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 歸檔頁（{@code GET /api/v1/articles/archive}）整份回應的 Redis 快取（PERF-04）。
 *
 * <p>歸檔頁是匿名、無分頁、低頻變動的全站清單，每次請求都組裝一次（全表掃描 + 標籤批次查詢）不划算。
 * 本元件以單一固定鍵 {@link RedisKeyConstant#ARTICLE_ARCHIVE_KEY} 快取整份 JSON。</p>
 *
 * <p><b>失效策略</b>：改變「已發布集合」的寫入路徑（發布、PUT 把草稿直接發布、下架、刪除已發布文章）
 * 在 DB commit <b>之後</b>呼叫 {@link #evict()}（見 {@code ArticleCommandSubService}）；
 * {@link RedisKeyConstant#ARTICLE_ARCHIVE_TTL_MINUTES} 分鐘的 TTL 是兜底。
 * 選擇 article 模組內同步失效、而不是訂閱 MQ 的 {@code article.*} 事件，理由有三：
 * (1) 事件經 broker 與 consumer 才失效，窗口比同步刪除長，而下架用於法務撤下，不該多出這段時間；
 * (2) 發布事件為 best-effort，事件遺失時快取就不會被清；(3) 這些寫入點都在 article 模組內，
 * 不必為自己的快取再開一組 queue／consumer。</p>
 *
 * <p><b>TTL 兜底涵蓋的缺口</b>：(a) Redis 當下不可達導致 {@link #evict()} 失敗；(b) 不經 article 模組的變更，
 * 例如 tag 模組改名／刪除標籤（歸檔回應含標籤名稱）；(c) 讀取端的競態——讀者在寫入 commit 前讀到舊 DB、
 * 失效之後才把舊結果回填。以上最壞情況皆為「陳舊至多 TTL」，不會永久不一致。</p>
 *
 * <p><b>快取只是優化，絕不能讓端點失敗</b>：讀取失敗或內容損毀一律視為未命中（呼叫端改查 DB 並覆寫），
 * 寫入與失效失敗只記 log、不外拋。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleArchiveCache {

    private static final TypeReference<List<ArticleArchiveResponse>> ARCHIVE_TYPE = new TypeReference<>() {
    };

    private static final Duration TTL = Duration.ofMinutes(RedisKeyConstant.ARTICLE_ARCHIVE_TTL_MINUTES);

    private final StringRedisTemplate stringRedisTemplate;

    private final ObjectMapper objectMapper;

    /**
     * 讀取快取的歸檔清單。
     *
     * @return 命中時為完整清單（空站台的空清單也是命中）；未命中、Redis 失敗或內容損毀時為 {@link Optional#empty()}
     */
    public Optional<List<ArticleArchiveResponse>> get() {
        try {
            String json = stringRedisTemplate.opsForValue().get(RedisKeyConstant.ARTICLE_ARCHIVE_KEY);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ARCHIVE_TYPE));
        } catch (Exception e) {
            log.warn("讀取歸檔頁快取失敗，改查 DB：{}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 寫入歸檔清單（含空清單），並設定 TTL。失敗只記 log，不影響本次回應。
     *
     * @param archive 要快取的完整歸檔清單
     */
    public void put(List<ArticleArchiveResponse> archive) {
        try {
            stringRedisTemplate.opsForValue().set(
                    RedisKeyConstant.ARTICLE_ARCHIVE_KEY, objectMapper.writeValueAsString(archive), TTL);
        } catch (Exception e) {
            log.warn("寫入歸檔頁快取失敗（不影響回應）：{}", e.getMessage());
        }
    }

    /**
     * 刪除歸檔快取，讓下一次請求重新查 DB。須在改變「已發布集合」的 DB 寫入 commit 之後呼叫。
     *
     * <p>失敗以 ERROR 記錄而不外拋：寫入已經 commit，不能因為失效失敗而回報操作失敗；
     * 但失效失敗代表歸檔頁最多會陳舊 {@link RedisKeyConstant#ARTICLE_ARCHIVE_TTL_MINUTES} 分鐘
     * （下架撤下時尤其值得告警），故不降為 warn。</p>
     */
    public void evict() {
        try {
            stringRedisTemplate.delete(RedisKeyConstant.ARTICLE_ARCHIVE_KEY);
        } catch (Exception e) {
            log.error("歸檔頁快取失效失敗，歸檔頁最多陳舊 {} 分鐘（TTL 兜底）：key={}, error={}",
                    RedisKeyConstant.ARTICLE_ARCHIVE_TTL_MINUTES, RedisKeyConstant.ARTICLE_ARCHIVE_KEY,
                    e.getMessage(), e);
        }
    }
}
