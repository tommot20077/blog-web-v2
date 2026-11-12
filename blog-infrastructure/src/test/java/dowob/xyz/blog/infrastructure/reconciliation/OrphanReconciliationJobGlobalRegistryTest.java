package dowob.xyz.blog.infrastructure.reconciliation;

import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link OrphanReconciliationJob} 以全域 registry 為指標來源的建構子測試。
 *
 * <p>沒有 actuator 的模組（測試應用程式）不會有 {@code MeterRegistry} bean；只接 {@code StringRedisTemplate}
 * 的建構子改用 {@link Metrics#globalRegistry}。正式環境 Spring Boot 會把自動配置的 registry 綁到全域
 * registry（{@code management.metrics.use-global-registry} 預設 true），指標不受影響。</p>
 *
 * <p>本測試自行掛上 {@link SimpleMeterRegistry} 並於結束時移除、關閉，避免跨測試汙染。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("OrphanReconciliationJob 全域 registry 建構子")
class OrphanReconciliationJobGlobalRegistryTest {

    private static final String LOCK_KEY = "lock:reconciliation:global:global_table";
    private static final String CURSOR_KEY = "reconciliation:cursor:global:global_table";

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq(LOCK_KEY), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(valueOps.get(CURSOR_KEY)).thenReturn(null);
        registry = new SimpleMeterRegistry();
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    @Test
    @DisplayName("只傳 StringRedisTemplate：指標記到全域 registry 上掛的 registry")
    void run_withRedisOnlyConstructor_recordsMetricsOnGlobalRegistry() {
        Metrics.addRegistry(registry);
        GlobalJob job = new GlobalJob(redis, 3, Set.of(2, 3));

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(registry.find("reconciliation.runs")
                .tag("module", "global").tag("table", "global_table").tag("result", "completed")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("reconciliation.orphans_removed")
                .tag("module", "global").tag("table", "global_table")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("全域 registry 沒有任何子 registry：照常執行，不拋例外")
    void run_withRedisOnlyConstructorAndNoRegistries_stillRuns() {
        GlobalJob job = new GlobalJob(redis, 3, Set.of(2, 3));

        assertThat(job.run()).isEqualTo(Result.COMPLETED);
        assertThat(job.removed).hasSize(1);
    }

    /** 測試用子類別：被參照的 UUID 為 1..n，owner 端只存在 {@code existing} 中的序號。 */
    private static final class GlobalJob extends OrphanReconciliationJob {

        private final int n;
        private final Set<Integer> existing;
        final List<UUID> removed = new ArrayList<>();

        GlobalJob(StringRedisTemplate redis, int n, Set<Integer> existing) {
            super(redis);
            this.n = n;
            this.existing = existing;
        }

        @Override
        protected String module() {
            return "global";
        }

        @Override
        protected String table() {
            return "global_table";
        }

        @Override
        protected List<UUID> fetchReferencedUuids(UUID afterExclusive, int limit) {
            List<UUID> all = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                UUID u = new UUID(0L, i);
                if (afterExclusive == null || u.compareTo(afterExclusive) > 0) {
                    all.add(u);
                }
            }
            return all.stream().limit(limit).toList();
        }

        @Override
        protected Set<UUID> filterExisting(Collection<UUID> uuids) {
            return uuids.stream()
                    .filter(u -> existing.contains((int) u.getLeastSignificantBits()))
                    .collect(java.util.stream.Collectors.toSet());
        }

        @Override
        protected int removeOrphans(Collection<UUID> missing) {
            removed.addAll(missing);
            return missing.size();
        }
    }
}
