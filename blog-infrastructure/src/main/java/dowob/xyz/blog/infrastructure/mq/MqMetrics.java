package dowob.xyz.blog.infrastructure.mq;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/**
 * MQ 消費指標的共用計數工具（套件內部使用）。
 *
 * <p>計數是旁路觀測，<b>絕不外拋</b>：任何 {@link RuntimeException}（MeterFilter 拋錯、tag 值為 {@code null} 等）
 * 都只記 warn，不得影響訊息的處理結果。全域 registry 未掛任何子 registry 時直接跳過——
 * 這只是省去建立 Counter 的成本，不是正確性所需。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
final class MqMetrics {

    private MqMetrics() {
    }

    /**
     * 將指定指標加一；<b>絕不外拋</b>。
     *
     * @param metric   指標名稱
     * @param tagKey   tag 名稱
     * @param tagValue tag 值
     */
    static void increment(String metric, String tagKey, String tagValue) {
        try {
            if (Metrics.globalRegistry.getRegistries().isEmpty()) {
                return;
            }
            Counter.builder(metric)
                    .tag(tagKey, tagValue)
                    .register(Metrics.globalRegistry)
                    .increment();
        } catch (RuntimeException e) {
            log.warn("指標 {}（{}={}）計數失敗，已忽略（不影響訊息處理結果）", metric, tagKey, tagValue, e);
        }
    }
}
