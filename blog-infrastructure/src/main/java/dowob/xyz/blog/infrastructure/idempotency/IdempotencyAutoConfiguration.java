package dowob.xyz.blog.infrastructure.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IdempotencyService 配置 — 只在有設定 datasource URL 時建立。
 *
 * <p>使用 {@code @ConditionalOnProperty(name="spring.datasource.url")} 判斷：
 * 有 datasource 的模組（article / series / comment 等）此 property 存在，
 * user / recommend 等 unit-only 模組不設 datasource → property 不存在 → bean 不建立。</p>
 *
 * <p>避免 user / recommend 等沒 datasource 的 unit-only 模組啟動時
 * 因找不到 JdbcTemplate 而失敗。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "spring.datasource.url")
    public IdempotencyService idempotencyService(JdbcTemplate jdbcTemplate) {
        return new IdempotencyService(jdbcTemplate);
    }

    /**
     * {@code processed_events} 每日清理排程（PERF-19）；與 {@link IdempotencyService} 同條件建立。
     *
     * @param jdbcTemplate JDBC 範本
     * @param txManager    交易管理員（每批刪除各自一個交易）
     * @param properties   清理設定
     * @return 清理排程 bean
     */
    @Bean
    @ConditionalOnProperty(name = "spring.datasource.url")
    public ProcessedEventsCleanupJob processedEventsCleanupJob(JdbcTemplate jdbcTemplate,
                                                               PlatformTransactionManager txManager,
                                                               IdempotencyProperties properties) {
        return new ProcessedEventsCleanupJob(jdbcTemplate, new TransactionTemplate(txManager), properties);
    }
}
