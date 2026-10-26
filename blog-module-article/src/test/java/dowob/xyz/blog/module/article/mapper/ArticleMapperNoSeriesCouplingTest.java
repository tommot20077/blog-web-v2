package dowob.xyz.blog.module.article.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * article 模組的 SQL 不得直接讀取 {@code series} 表、也不得再引用已移除的 {@code articles.series_id} 欄位（M6-b）。
 *
 * <p>系列以 {@code articles.series_uuid} 儲存（跨模組識別一律為 UUID、沒有 FK），需要系列標題等資訊時經
 * {@code SeriesFacade.batchGetSeriesBasicInfoByUuids} 批次取得。本測試掃描 article 全部 mapper 介面的 SQL 註解、
 * 介面常數與 {@code ArticleRecommendMapper.xml}，任何一處回到 {@code JOIN series}／{@code FROM series}／
 * {@code series_id} 即失敗。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("article mapper SQL 不耦合 series 表與 series_id")
class ArticleMapperNoSeriesCouplingTest {

    private static final Pattern SERIES_TABLE = Pattern.compile("(?i)\\b(join|from)\\s+series\\b");

    private static final Pattern SERIES_ID_COLUMN = Pattern.compile("(?i)\\bseries_id\\b");

    /**
     * 收集 mapper 介面上所有 SQL 註解與字串常數。
     *
     * @param mapper mapper 介面
     * @return 全部 SQL 文字
     */
    private static List<String> annotationSql(Class<?> mapper) throws IllegalAccessException {
        List<String> sql = new ArrayList<>();
        for (Method m : mapper.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Select.class)) {
                sql.add(String.join("\n", m.getAnnotation(Select.class).value()));
            }
            if (m.isAnnotationPresent(Update.class)) {
                sql.add(String.join("\n", m.getAnnotation(Update.class).value()));
            }
            if (m.isAnnotationPresent(Insert.class)) {
                sql.add(String.join("\n", m.getAnnotation(Insert.class).value()));
            }
            if (m.isAnnotationPresent(Delete.class)) {
                sql.add(String.join("\n", m.getAnnotation(Delete.class).value()));
            }
        }
        for (Field f : mapper.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                f.setAccessible(true);
                sql.add((String) f.get(null));
            }
        }
        return sql;
    }

    @Test
    @DisplayName("ArticleMapper 的 SQL 沒有 JOIN／FROM series，也沒有 series_id")
    void articleMapper_hasNoSeriesJoinAndNoSeriesId() throws Exception {
        List<String> sql = annotationSql(ArticleMapper.class);

        assertThat(sql).isNotEmpty();
        assertThat(sql).noneMatch(s -> SERIES_TABLE.matcher(s).find());
        assertThat(sql).noneMatch(s -> SERIES_ID_COLUMN.matcher(s).find());
    }

    @Test
    @DisplayName("ArticleSeriesCleanupMapper 與 ArticleRecommendMapper 的註解 SQL 沒有 JOIN／FROM series，也沒有 series_id")
    void otherMappers_haveNoSeriesJoinAndNoSeriesId() throws Exception {
        List<String> sql = new ArrayList<>(annotationSql(ArticleSeriesCleanupMapper.class));
        sql.addAll(annotationSql(ArticleRecommendMapper.class));

        assertThat(sql).isNotEmpty();
        assertThat(sql).noneMatch(s -> SERIES_TABLE.matcher(s).find());
        assertThat(sql).noneMatch(s -> SERIES_ID_COLUMN.matcher(s).find());
    }

    @Test
    @DisplayName("ArticleRecommendMapper.xml 沒有 JOIN／FROM series，也沒有 series_id")
    void recommendMapperXml_hasNoSeriesJoinAndNoSeriesId() throws Exception {
        try (InputStream in = ArticleRecommendMapper.class.getClassLoader()
                .getResourceAsStream("mapper/ArticleRecommendMapper.xml")) {
            assertThat(in).as("mapper/ArticleRecommendMapper.xml 必須在 classpath").isNotNull();
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(SERIES_TABLE.matcher(xml).find()).isFalse();
            assertThat(SERIES_ID_COLUMN.matcher(xml).find()).isFalse();
        }
    }

    @Test
    @DisplayName("掃描範圍確實涵蓋 series_uuid 相關的 SQL（防止測試因反射抓不到註解而空轉）")
    void scanCoversSeriesUuidSql() throws Exception {
        assertThat(annotationSql(ArticleMapper.class)).anyMatch(s -> s.contains("series_uuid"));
        assertThat(annotationSql(ArticleSeriesCleanupMapper.class)).anyMatch(s -> s.contains("series_uuid"));
    }
}
