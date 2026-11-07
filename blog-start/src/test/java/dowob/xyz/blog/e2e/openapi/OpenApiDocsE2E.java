package dowob.xyz.blog.e2e.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * springdoc 實際產出驗證（W7-C2／ARCH-14）：openapi.json 是前後端契約的唯一真相，
 * 這裡以真實 context 打 {@code /v3/api-docs}，驗證每個 operation 都有 summary，
 * 並且共通錯誤回應（401／403／429／500）確實由標註推導出來、reindex 的 202／409 有標示。
 *
 * <p>e2e profile 開啟 springdoc（見 {@code application-e2e.yaml}）。產出的 JSON 另存
 * {@code target/openapi/openapi.json} 供人工檢視。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("OpenAPI 產出驗證 E2E")
class OpenApiDocsE2E extends AbstractE2ETest {

    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete");

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    /** 正式程式（module 套件）的 handler；e2e context 另含 architecture 守衛用的 fixture controller，不屬契約 */
    private List<Map.Entry<RequestMappingInfo, HandlerMethod>> moduleHandlers() {
        return handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(e -> e.getValue().getBeanType().getPackageName().startsWith("dowob.xyz.blog.module"))
                .toList();
    }

    /** 取得 {@code /v3/api-docs}，去掉非 module 的路徑後存到 target/openapi/openapi.json 並回傳 */
    private JsonNode fetchDocs() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode docs = (ObjectNode) objectMapper.readTree(body);
        Set<String> modulePaths = new HashSet<>();
        moduleHandlers().forEach(e -> modulePaths.addAll(e.getKey().getPatternValues()));
        ObjectNode paths = (ObjectNode) docs.get("paths");
        List<String> foreign = new ArrayList<>();
        paths.fieldNames().forEachRemaining(n -> {
            if (!modulePaths.contains(n)) {
                foreign.add(n);
            }
        });
        paths.remove(foreign);
        Path out = Path.of("target", "openapi", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(docs));
        return docs;
    }

    private static JsonNode operation(JsonNode docs, String path, String method) {
        return docs.path("paths").path(path).path(method);
    }

    @Test
    @DisplayName("每個 operation 都有非空白 summary，且至少有 500 回應")
    void everyOperationHasSummaryAnd500() throws Exception {
        JsonNode docs = fetchDocs();
        List<String> missingSummary = new ArrayList<>();
        List<String> missing500 = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, JsonNode> path : docs.path("paths").properties()) {
            for (String method : HTTP_METHODS) {
                JsonNode op = path.getValue().path(method);
                if (op.isMissingNode()) {
                    continue;
                }
                total++;
                if (op.path("summary").asText("").isBlank()) {
                    missingSummary.add(method.toUpperCase() + " " + path.getKey());
                }
                if (op.path("responses").path("500").isMissingNode()) {
                    missing500.add(method.toUpperCase() + " " + path.getKey());
                }
            }
        }
        assertThat(total).as("operation 總數").isGreaterThan(80);
        assertThat(missingSummary).as("缺 summary 的 operation").isEmpty();
        assertThat(missing500).as("缺 500 的 operation").isEmpty();
    }

    @Test
    @DisplayName("帶 @RateLimited 的 handler 有 429 與 Retry-After；帶 @PreAuthorize 的 handler 有 401")
    void commonErrorResponsesAreDerivedFromAnnotations() throws Exception {
        JsonNode docs = fetchDocs();
        List<String> missing429 = new ArrayList<>();
        List<String> missing401 = new ArrayList<>();
        int rateLimited = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : moduleHandlers()) {
            HandlerMethod handler = entry.getValue();
            boolean limited = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), RateLimited.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RateLimited.class);
            boolean secured = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class);
            for (String path : entry.getKey().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    JsonNode op = operation(docs, path, method.name().toLowerCase());
                    String label = method + " " + path;
                    assertThat(op.isMissingNode()).as("openapi 缺少 " + label).isFalse();
                    if (limited) {
                        rateLimited++;
                        if (op.path("responses").path("429").isMissingNode()
                                || op.path("responses").path("429").path("headers").path("Retry-After").isMissingNode()) {
                            missing429.add(label);
                        }
                    }
                    if (secured && op.path("responses").path("401").isMissingNode()) {
                        missing401.add(label);
                    }
                }
            }
        }
        assertThat(rateLimited).as("帶 @RateLimited 的 operation 數").isGreaterThan(10);
        assertThat(missing429).as("缺 429／Retry-After").isEmpty();
        assertThat(missing401).as("缺 401").isEmpty();
    }

    @Test
    @DisplayName("reindex 標示 202 與 409（A0501）；管理端點有 403")
    void reindexDocumentsAcceptedAndConflict() throws Exception {
        JsonNode op = operation(fetchDocs(), "/api/v1/admin/search/reindex", "post");
        assertThat(op.path("responses").path("202").isMissingNode()).isFalse();
        assertThat(op.path("responses").path("409").path("description").asText()).contains("A0501");
        assertThat(op.path("responses").path("403").isMissingNode()).isFalse();
    }

    @Test
    @DisplayName("登入主體（AuthUser）不得被 springdoc 展開成 query 參數（uuid／role）")
    void authPrincipalIsNotExposedAsQueryParameter() throws Exception {
        JsonNode docs = fetchDocs();
        List<String> leaked = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : docs.path("paths").properties()) {
            for (String method : HTTP_METHODS) {
                for (JsonNode param : path.getValue().path(method).path("parameters")) {
                    String name = param.path("name").asText();
                    if ("query".equals(param.path("in").asText()) && (name.equals("role") || name.equals("uuid"))) {
                        leaked.add(method.toUpperCase() + " " + path.getKey() + " ?" + name);
                    }
                }
            }
        }
        assertThat(leaked).as("AuthUser 欄位洩漏成 query 參數").isEmpty();
    }
}
