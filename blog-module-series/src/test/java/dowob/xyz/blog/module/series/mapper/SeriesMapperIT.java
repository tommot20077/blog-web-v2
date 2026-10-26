package dowob.xyz.blog.module.series.mapper;

import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.support.SeededUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SeriesMapper} 在 M4-b 新增的 SQL 對真實 PostgreSQL 的整合測試：
 * 寫入時的 slug 衝突處理（RACE-15）、局部更新、以 UUID 扣計數，以及以 UUID 為鍵的列表查詢。
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("SeriesMapper（M4-b 新增的 SQL）")
class SeriesMapperIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    @Autowired private SeriesMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private FileFacade fileFacade;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.execute("DELETE FROM series");
    }

    private Series newSeries(String slug) {
        Series s = new Series();
        s.setUuid(UUID.randomUUID());
        s.setTitle("T-" + slug);
        s.setSlug(slug);
        s.setDescription("d-" + slug);
        s.setCoverImageUrl("https://img/" + slug);
        s.setAuthorUuid(SeededUsers.USER1_UUID);
        s.setArticleCount(0);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return s;
    }

    private long idOf(UUID uuid) {
        return jdbc.queryForObject("SELECT id FROM series WHERE uuid = ?", Long.class, uuid);
    }

    // ─── insertIfSlugAbsent（RACE-15）──────────────────────────────────────

    @Test
    @DisplayName("insertIfSlugAbsent：slug 未被使用 → 影響 1 列，欄位（含 author_uuid、預設的時間戳）寫入正確")
    void insertIfSlugAbsent_whenFree_insertsRow() {
        Series s = newSeries("mapper-insert");

        int affected = mapper.insertIfSlugAbsent(s);

        assertThat(affected).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT author_uuid FROM series WHERE uuid = ?", UUID.class, s.getUuid()))
                .isEqualTo(SeededUsers.USER1_UUID);
        assertThat(jdbc.queryForObject("SELECT title || '|' || slug || '|' || description || '|' || article_count "
                + "FROM series WHERE uuid = ?", String.class, s.getUuid()))
                .isEqualTo("T-mapper-insert|mapper-insert|d-mapper-insert|0");
        assertThat(jdbc.queryForObject("SELECT created_at FROM series WHERE uuid = ?", LocalDateTime.class, s.getUuid()))
                .as("created_at 為呼叫端傳入的 Java 時間").isEqualTo(s.getCreatedAt());
        assertThat(jdbc.queryForObject("SELECT updated_at FROM series WHERE uuid = ?", LocalDateTime.class, s.getUuid()))
                .isEqualTo(s.getUpdatedAt());
    }

    @Test
    @DisplayName("insertIfSlugAbsent：slug 已被使用 → 影響 0 列且不拋例外，原本的列不被改動")
    void insertIfSlugAbsent_whenSlugTaken_affectsNothingAndDoesNotThrow() {
        Series first = newSeries("mapper-dup");
        mapper.insertIfSlugAbsent(first);
        Series second = newSeries("mapper-dup");
        second.setTitle("should not win");

        int affected = mapper.insertIfSlugAbsent(second);

        assertThat(affected).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM series WHERE slug = ?", Integer.class, "mapper-dup"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title FROM series WHERE slug = ?", String.class, "mapper-dup"))
                .isEqualTo("T-mapper-dup");
    }

    // ─── updateBasicFields（RACE-15）───────────────────────────────────────

    @Test
    @DisplayName("updateBasicFields：只更新非 null 的欄位，其餘保持原值（與舊的「僅非 null 欄位生效」語意一致）")
    void updateBasicFields_updatesOnlyNonNullFields() {
        Series s = newSeries("mapper-upd");
        mapper.insertIfSlugAbsent(s);

        int affected = mapper.updateBasicFields(idOf(s.getUuid()), "new title", null, null, null, LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT title || '|' || slug || '|' || description || '|' || cover_image_url "
                + "FROM series WHERE uuid = ?", String.class, s.getUuid()))
                .isEqualTo("new title|mapper-upd|d-mapper-upd|https://img/mapper-upd");
    }

    @Test
    @DisplayName("updateBasicFields：四個欄位都給值 → 全部更新，author_uuid 與 article_count 不受影響")
    void updateBasicFields_allFields_updatesAllButAuthorAndCount() {
        Series s = newSeries("mapper-upd-all");
        s.setArticleCount(4);
        mapper.insertIfSlugAbsent(s);

        mapper.updateBasicFields(idOf(s.getUuid()), "t2", "mapper-upd-all-2", "d2", "https://img/2", LocalDateTime.now());

        assertThat(jdbc.queryForObject("SELECT title || '|' || slug || '|' || description || '|' || cover_image_url "
                + "|| '|' || article_count || '|' || author_uuid FROM series WHERE uuid = ?", String.class, s.getUuid()))
                .isEqualTo("t2|mapper-upd-all-2|d2|https://img/2|4|" + SeededUsers.USER1_UUID);
    }

    @Test
    @DisplayName("updateBasicFields：改成已被他人使用的 slug → DuplicateKeyException（service 據此轉 S0104）")
    void updateBasicFields_toTakenSlug_throwsDuplicateKeyException() {
        Series a = newSeries("mapper-taken");
        Series b = newSeries("mapper-other");
        mapper.insertIfSlugAbsent(a);
        mapper.insertIfSlugAbsent(b);

        assertThatThrownBy(() -> mapper.updateBasicFields(idOf(b.getUuid()), null, "mapper-taken", null, null, LocalDateTime.now()))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("updateBasicFields：updated_at 寫入呼叫端傳入的 Java 時間")
    void updateBasicFields_writesGivenUpdatedAt() {
        Series s = newSeries("mapper-upd-time");
        mapper.insertIfSlugAbsent(s);
        LocalDateTime given = LocalDateTime.of(2031, 5, 6, 7, 8, 9);

        mapper.updateBasicFields(idOf(s.getUuid()), "t", null, null, null, given);

        assertThat(jdbc.queryForObject("SELECT updated_at FROM series WHERE uuid = ?", LocalDateTime.class, s.getUuid()))
                .isEqualTo(given);
    }

    @Test
    @DisplayName("updateBasicFields：slug 改成自己目前的值 → 不衝突")
    void updateBasicFields_toOwnSlug_isNotAConflict() {
        Series s = newSeries("mapper-own");
        mapper.insertIfSlugAbsent(s);

        int affected = mapper.updateBasicFields(idOf(s.getUuid()), "t", "mapper-own", null, null, LocalDateTime.now());

        assertThat(affected).isEqualTo(1);
    }

    // ─── decrementArticleCountByUuid ────────────────────────────────────────

    @Test
    @DisplayName("decrementArticleCountByUuid：計數 > 0 時 -1；已為 0 時不再扣（守衛）；未知 uuid 影響 0 列")
    void decrementArticleCountByUuid_guardsAgainstUnderflow() {
        Series s = newSeries("mapper-dec");
        s.setArticleCount(1);
        mapper.insertIfSlugAbsent(s);

        assertThat(mapper.decrementArticleCountByUuid(s.getUuid())).isEqualTo(1);
        assertThat(mapper.decrementArticleCountByUuid(s.getUuid())).isZero();
        assertThat(mapper.decrementArticleCountByUuid(UUID.randomUUID())).isZero();
        assertThat(jdbc.queryForObject("SELECT article_count FROM series WHERE uuid = ?", Integer.class, s.getUuid()))
                .isZero();
    }

    // ─── 以 UUID 為鍵的列表查詢 ──────────────────────────────────────────────

    @Test
    @DisplayName("findAllUuidsOrderByCreatedAtDesc：回傳全部 series 的 uuid，最新優先")
    void findAllUuidsOrderByCreatedAtDesc_returnsNewestFirst() {
        Series oldest = newSeries("mapper-list-1");
        Series middle = newSeries("mapper-list-2");
        Series newest = newSeries("mapper-list-3");
        for (Series s : List.of(oldest, middle, newest)) {
            mapper.insertIfSlugAbsent(s);
        }
        jdbc.update("UPDATE series SET created_at = TIMESTAMP '2026-01-01 00:00:00' WHERE uuid = ?", oldest.getUuid());
        jdbc.update("UPDATE series SET created_at = TIMESTAMP '2026-02-01 00:00:00' WHERE uuid = ?", middle.getUuid());
        jdbc.update("UPDATE series SET created_at = TIMESTAMP '2026-03-01 00:00:00' WHERE uuid = ?", newest.getUuid());

        assertThat(mapper.findAllUuidsOrderByCreatedAtDesc())
                .containsExactly(newest.getUuid(), middle.getUuid(), oldest.getUuid());
    }

    @Test
    @DisplayName("findByUuids：回傳指定 uuid 的 series（含 author_uuid、不含 article_count），不認識的 uuid 略過")
    void findByUuids_returnsRequestedRowsWithAuthorUuid() {
        Series a = newSeries("mapper-by-1");
        a.setArticleCount(9);
        Series b = newSeries("mapper-by-2");
        b.setAuthorUuid(SeededUsers.USER2_UUID);
        Series c = newSeries("mapper-by-3");
        for (Series s : List.of(a, b, c)) {
            mapper.insertIfSlugAbsent(s);
        }

        List<Series> rows = mapper.findByUuids(List.of(a.getUuid(), b.getUuid(), UUID.randomUUID()));

        assertThat(rows).extracting(Series::getUuid).containsExactlyInAnyOrder(a.getUuid(), b.getUuid());
        Series rowB = rows.stream().filter(r -> r.getUuid().equals(b.getUuid())).findFirst().orElseThrow();
        assertThat(rowB.getAuthorUuid()).isEqualTo(SeededUsers.USER2_UUID);
        assertThat(rowB.getTitle()).isEqualTo("T-mapper-by-2");
        assertThat(rowB.getSlug()).isEqualTo("mapper-by-2");
        assertThat(rowB.getCreatedAt()).isNotNull();
        assertThat(rows).extracting(Series::getArticleCount)
                .as("列表查詢不投影 article_count（改用即時計數）").containsOnlyNulls();
    }
}
