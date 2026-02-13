package dowob.xyz.blog.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;

/**
 * JDBC 審計配置
 * 啟用 @CreatedDate, @LastModifiedDate 自動填充
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
@EnableJdbcAuditing
public class AuditingConfig {
}
