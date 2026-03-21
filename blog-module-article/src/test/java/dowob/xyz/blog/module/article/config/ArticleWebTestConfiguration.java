package dowob.xyz.blog.module.article.config;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 文章模組 Web 層測試用最小化 Spring Boot 配置
 *
 * <p>
 * 僅供 {@code @WebMvcTest} 使用。不啟用 JDBC、RabbitMQ、Elasticsearch 等
 * 非 Web 層相關的自動配置，避免載入不必要的 bean。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootApplication(scanBasePackages = {
        "dowob.xyz.blog.common",
        "dowob.xyz.blog.infrastructure.config",
        "dowob.xyz.blog.infrastructure.security",
        "dowob.xyz.blog.module.article.controller"
})
public class ArticleWebTestConfiguration {
}
