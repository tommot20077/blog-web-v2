package dowob.xyz.blog.e2e;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E Smoke Test — 驗證基礎建設正常運作
 */
@DisplayName("E2E Smoke Test")
class SmokeE2E extends AbstractE2ETest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ElasticsearchOperations esOps;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Test
    @DisplayName("Spring Context 正常載入，所有 Bean 可注入")
    void contextLoads() {
        assertThat(mockMvc).isNotNull();
        assertThat(objectMapper).isNotNull();
        assertThat(jdbcTemplate).isNotNull();
        assertThat(esOps).isNotNull();
        assertThat(databaseCleaner).isNotNull();
    }

    @Test
    @DisplayName("Flyway migration 成功，users 表存在")
    void flywayMigrationSucceeded() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'users'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("Elasticsearch 索引已建立")
    void elasticsearchIndexExists() {
        assertThat(esOps.indexOps(
                dowob.xyz.blog.module.search.document.ArticleDocument.class).exists())
                .isTrue();
    }

    @Test
    @DisplayName("公開 API 端點可存取")
    void publicEndpointAccessible() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk());
    }
}
