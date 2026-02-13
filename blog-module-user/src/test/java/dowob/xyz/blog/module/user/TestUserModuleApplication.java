package dowob.xyz.blog.module.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ElasticsearchDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.elasticsearch.ReactiveElasticsearchRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration;
import org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration;

/**
 * 測試用 Spring Boot 啟動類（僅供整合測試使用）
 *
 * <p>
 * 此類僅存在於 {@code src/test/java}，用於為 {@code blog-module-user} 模組
 * 提供一個最小化的 Spring Context，以支援 Testcontainers 整合測試。
 * 實際生產啟動類位於 {@code blog-start} 模組。
 * </p>
 *
 * <p>
 * 排除清單說明：
 * <ul>
 *   <li>RabbitMQ：整合測試只需 PostgreSQL + Redis，不需要 RabbitMQ</li>
 *   <li>Elasticsearch：整合測試不涉及搜尋功能</li>
 * </ul>
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootApplication(scanBasePackages = "dowob.xyz.blog", exclude = {
        RabbitAutoConfiguration.class,
        ElasticsearchDataAutoConfiguration.class,
        ElasticsearchClientAutoConfiguration.class,
        ElasticsearchRestClientAutoConfiguration.class,
        ReactiveElasticsearchRepositoriesAutoConfiguration.class
})
public class TestUserModuleApplication {

    /**
     * 測試啟動方法（通常不直接呼叫）
     *
     * @param args 命令行參數
     */
    public static void main(String[] args) {
        SpringApplication.run(TestUserModuleApplication.class, args);
    }
}
