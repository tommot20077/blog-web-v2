package dowob.xyz.blog.module.article.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * article 模組的 SQL 不得直接讀取 {@code users} 表、也不得再引用已移除的 {@code articles.author_id} 欄位（M6-a）。
 *
 * <p>跨模組識別一律為 UUID：作者以 {@code articles.author_uuid} 儲存，需要暱稱等資訊時經
 * {@code UserFacade.getDisplayInfoByUuids} 批次取得。本測試掃描兩個 mapper 介面的全部 SQL 註解、
 * 介面常數（公開列表的 WHERE 片段）與 {@code ArticleRecommendMapper.xml}，任何一處回到
 * {@code JOIN users}／{@code FROM users}／{@code author_id} 即失敗。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("article mapper SQL 不耦合 users 表與 author_id")
class ArticleMapperNoUsersCouplingTest {

    private static final Pattern USERS_TABLE = Pattern.compile("(?i)\\b(join|from)\\s+users\\b");

    private static final Pattern AUTHOR_ID_COLUMN = Pattern.compile("(?i)\\bauthor_id\\b");

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

    /**
     * 讀取 classpath 上的 mapper XML。
     *
     * @return XML 全文
     */
    private static String recommendMapperXml() throws IOException {
        try (InputStream in = ArticleRecommendMapper.class.getClassLoader()
                .getResourceAsStream("mapper/ArticleRecommendMapper.xml")) {
            assertThat(in).as("mapper/ArticleRecommendMapper.xml 必須在 classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("ArticleMapper 的 SQL（含公開列表 WHERE 常數）沒有 JOIN／FROM users，也沒有 author_id")
    void articleMapper_hasNoUsersJoinAndNoAuthorId() throws Exception {
        List<String> sql = annotationSql(ArticleMapper.class);

        assertThat(sql).isNotEmpty();
        assertThat(sql).noneMatch(s -> USERS_TABLE.matcher(s).find());
        assertThat(sql).noneMatch(s -> AUTHOR_ID_COLUMN.matcher(s).find());
    }

    @Test
    @DisplayName("ArticleRecommendMapper 的註解 SQL 沒有 JOIN／FROM users，也沒有 author_id")
    void recommendMapperAnnotations_haveNoUsersJoinAndNoAuthorId() throws Exception {
        List<String> sql = annotationSql(ArticleRecommendMapper.class);

        assertThat(sql).isNotEmpty();
        assertThat(sql).noneMatch(s -> USERS_TABLE.matcher(s).find());
        assertThat(sql).noneMatch(s -> AUTHOR_ID_COLUMN.matcher(s).find());
    }

    @Test
    @DisplayName("ArticleRecommendMapper.xml 沒有 JOIN／FROM users，也沒有 author_id")
    void recommendMapperXml_hasNoUsersJoinAndNoAuthorId() throws Exception {
        String xml = recommendMapperXml();

        assertThat(USERS_TABLE.matcher(xml).find()).isFalse();
        assertThat(AUTHOR_ID_COLUMN.matcher(xml).find()).isFalse();
    }

    @Test
    @DisplayName("掃描範圍確實涵蓋兩個 mapper 的方法（防止測試因反射抓不到註解而空轉）")
    void scanCoversMapperMethods() throws Exception {
        assertThat(Arrays.stream(ArticleMapper.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Select.class)).count()).isGreaterThan(10);
        assertThat(annotationSql(ArticleMapper.class)).anyMatch(s -> s.contains("articles"));
        assertThat(annotationSql(ArticleRecommendMapper.class)).anyMatch(s -> s.contains("articles"));
    }
}
