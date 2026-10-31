package dowob.xyz.blog.infrastructure.mq;

import org.springframework.lang.NonNull;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.RetryContextCache;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 有界的有狀態重試 context 快取：超過容量時<b>淘汰最舊的</b>，而不是拋例外。
 *
 * <p><b>為什麼不用預設的 {@code MapRetryContextCache}</b>：它超過容量（預設 4096）會拋
 * {@code RetryCacheCapacityExceededException}，使「之後所有新失敗的訊息」都以該例外失敗。
 * 而本專案有些訊息會在重試中途離開而來不及清除自己的 context——例如訊息轉換失敗由容器直接拒絕（不經 recoverer）、
 * 應用程式重啟。若發送端一次發出大量壞訊息，預設快取會被這些殘留塞滿。本快取改為淘汰最舊的：
 * 最壞情況只是某則「很久以前開始重試」的訊息被重新計次，不會讓整條消費管線失效。</p>
 *
 * <p>執行緒安全（所有操作 synchronized；容器的多條 consumer 執行緒共用同一個快取）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class BoundedRetryContextCache implements RetryContextCache {

    /** 以插入順序保存；超量時淘汰最舊的 */
    private final Map<Object, RetryContext> map;

    /**
     * 建立快取。
     *
     * @param capacity 最大容量（超過即淘汰最舊的）；必須為正數
     */
    public BoundedRetryContextCache(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity 必須為正數：" + capacity);
        }
        this.map = new LinkedHashMap<>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, RetryContext> eldest) {
                return size() > capacity;
            }
        };
    }

    @Override
    public synchronized RetryContext get(Object key) {
        return map.get(key);
    }

    @Override
    public synchronized void put(@NonNull Object key, @NonNull RetryContext context) {
        map.put(key, context);
    }

    @Override
    public synchronized void remove(Object key) {
        map.remove(key);
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        return map.containsKey(key);
    }
}
