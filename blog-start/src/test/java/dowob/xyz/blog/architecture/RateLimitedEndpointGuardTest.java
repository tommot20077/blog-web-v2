package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dowob.xyz.blog.infrastructure.ratelimit.RateLimitProperties;
import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 應用層限流守衛（SEC-23 / A4）：指定清單上的 handler 必須帶有對應的 {@code @RateLimited} 規則，
 * 防止日後新增或改名端點時漏掉限流；同時 {@code application.yaml} 的門檻必須等於 Yuan 2026-11-07 拍板值。
 *
 * <p>寫法參考 {@link EndpointAuthorizationTest}。清單是 A4 決策表的機器可讀投影：新增需限流的端點時，
 * 先在決策文件確認門檻，再同步更新本清單與 yaml。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("應用層限流守衛（A4）")
class RateLimitedEndpointGuardTest {

    /** 規則名稱常量，避免字串散落 */
    private static final String COMMENT_WRITE = "comment-write";
    private static final String INTERACTION_WRITE = "interaction-write";
    private static final String UPLOAD = "upload";
    private static final String ARTICLE_WRITE = "article-write";
    private static final String SEARCH = "search";

    /**
     * 必須帶規則的 handler：{@code 類別#方法} → 規則名稱。
     *
     * <p>刪除類（取消按讚、取消收藏、刪留言／文章／檔案／劃線）與讀取類不在清單內（決策 A4：寫入類才限流）；
     * 搜尋建議 {@code /search/suggest} 與文章狀態流轉（submit／withdraw／publish）也未列入，見 W7-b 回報的待確認項。</p>
     */
    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("CommentController#create", COMMENT_WRITE),
            Map.entry("CommentController#edit", COMMENT_WRITE),
            Map.entry("CommentLikeController#like", INTERACTION_WRITE),
            Map.entry("ArticleLikeController#like", INTERACTION_WRITE),
            Map.entry("BookmarkController#bookmark", INTERACTION_WRITE),
            Map.entry("HighlightController#create", INTERACTION_WRITE),
            Map.entry("HighlightController#update", INTERACTION_WRITE),
            Map.entry("ReadingProgressController#update", INTERACTION_WRITE),
            Map.entry("FileController#uploadFile", UPLOAD),
            Map.entry("ArticleController#createArticle", ARTICLE_WRITE),
            Map.entry("ArticleController#updateArticle", ARTICLE_WRITE),
            Map.entry("SearchController#search", SEARCH)));

    private static JavaClasses importProduction() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog.module");
    }

    private static Map<String, String> actualRules(JavaClasses classes) {
        Map<String, String> actual = new TreeMap<>();
        for (JavaMethod method : classes.stream().flatMap(c -> c.getMethods().stream()).toList()) {
            method.tryGetAnnotationOfType(RateLimited.class).ifPresent(a ->
                    actual.put(method.getOwner().getSimpleName() + "#" + method.getName(), a.value()));
        }
        return actual;
    }

    @Test
    @DisplayName("指定端點必須帶有對應的 @RateLimited 規則")
    void expectedEndpointsMustDeclareRule() {
        Map<String, String> actual = actualRules(importProduction());

        assertThat(actual)
                .as("A4 清單上的端點缺少 @RateLimited 或規則名稱不符；新增寫入端點請依決策表補上")
                .containsAllEntriesOf(EXPECTED);
    }

    @Test
    @DisplayName("防腐：@RateLimited 只能出現在清單內的端點（新增限流端點須有意識地更新清單與 yaml）")
    void noUnlistedRateLimitedEndpoints() {
        assertThat(actualRules(importProduction()).keySet())
                .as("出現清單外的 @RateLimited handler，請同步更新 EXPECTED 與 security.md 規則表")
                .isSubsetOf(EXPECTED.keySet());
    }

    @Test
    @DisplayName("application.yaml 的規則齊全，且門檻、窗口、計數主體等於 A4 決策")
    void yamlMatchesDecisionTable() throws Exception {
        RateLimitProperties props = bindProductionYaml();

        assertThat(props.rules().keySet()).containsExactlyInAnyOrder(
                COMMENT_WRITE, INTERACTION_WRITE, UPLOAD, ARTICLE_WRITE, SEARCH);
        assertRule(props, COMMENT_WRITE, 10, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, INTERACTION_WRITE, 60, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, UPLOAD, 30, Duration.ofHours(1), RateLimitProperties.Subject.USER);
        assertRule(props, ARTICLE_WRITE, 30, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, SEARCH, 30, Duration.ofMinutes(1), RateLimitProperties.Subject.IP);
        assertThat(EXPECTED.values()).allSatisfy(rule -> assertThat(props.rules()).containsKey(rule));
    }

    private static void assertRule(RateLimitProperties props, String name, int limit, Duration window,
                                   RateLimitProperties.Subject subject) {
        assertThat(props.rules().get(name))
                .as("規則 %s", name)
                .isEqualTo(new RateLimitProperties.Rule(limit, window, subject));
    }

    /** 以 Spring Boot 的 placeholder 規則解析 application.yaml（環境變數未設定時取預設值） */
    private static RateLimitProperties bindProductionYaml() throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        sources.addFirst(new MapPropertySource("empty-env", Map.of()));
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("app.rate-limit", Bindable.of(RateLimitProperties.class))
                .orElseThrow(() -> new AssertionError("application.yaml 缺少 app.rate-limit"));
    }
}
