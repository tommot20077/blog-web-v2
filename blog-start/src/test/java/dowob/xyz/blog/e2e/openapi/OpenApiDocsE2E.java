package dowob.xyz.blog.e2e.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.ResponseEntity;
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

    private static List<String> toList(java.util.Iterator<String> names) {
        List<String> list = new ArrayList<>();
        names.forEachRemaining(list::add);
        return list;
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

    private static final String ERROR_SCHEMA_REF = "#/components/schemas/ApiErrorResponse";

    /**
     * 刻意讓錯誤回應 body 帶資料的例外（key 為 {@code METHOD path status}）：
     * reindex 衝突時 {@code data} 是進行中的作業，並非 null，故在 handler 明確指定 content。
     */
    private static final Set<String> ERROR_BODY_EXEMPTIONS = Set.of(
            "POST /api/v1/admin/search/reindex 409");

    @Test
    @DisplayName("每個 operation 至少有一個 2xx 或 304 回應")
    void everyOperationHasSuccessResponse() throws Exception {
        JsonNode docs = fetchDocs();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : docs.path("paths").properties()) {
            for (String method : HTTP_METHODS) {
                JsonNode op = path.getValue().path(method);
                if (op.isMissingNode()) {
                    continue;
                }
                boolean hasSuccess = false;
                for (String status : toList(op.path("responses").fieldNames())) {
                    if (status.startsWith("2") || status.equals("304")) {
                        hasSuccess = true;
                    }
                }
                if (!hasSuccess) {
                    missing.add(method.toUpperCase() + " " + path.getKey());
                }
            }
        }
        assertThat(missing).as("沒有任何 2xx／304 回應的 operation").isEmpty();
    }

    @Test
    @DisplayName("回傳型別非 void 的 handler，其 2xx 回應有 content 且 $ref 對應回傳型別")
    void successResponseCarriesReturnTypeSchema() throws Exception {
        JsonNode docs = fetchDocs();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : moduleHandlers()) {
            HandlerMethod handler = entry.getValue();
            ResolvableType returnType = ResolvableType.forMethodReturnType(handler.getMethod());
            if (returnType.resolve(Object.class) == void.class) {
                continue;
            }
            ResolvableType body = ResponseEntity.class.isAssignableFrom(returnType.resolve(Object.class))
                    ? returnType.getGeneric(0) : returnType;
            for (String path : entry.getKey().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    JsonNode op = operation(docs, path, method.name().toLowerCase());
                    String label = method + " " + path;
                    boolean checked = false;
                    for (Map.Entry<String, JsonNode> response : op.path("responses").properties()) {
                        if (!response.getKey().startsWith("2")) {
                            continue;
                        }
                        checked = true;
                        String ref = response.getValue().path("content").path("*/*").path("schema").path("$ref").asText("");
                        if (ref.isEmpty()) {
                            problems.add(label + " " + response.getKey() + " 缺 content／$ref");
                        } else if (!ref.startsWith("#/components/schemas/ApiResponse")
                                || !containsGenericNames(ref, body)) {
                            problems.add(label + " " + response.getKey() + " $ref=" + ref + " 與回傳型別 " + body + " 不符");
                        }
                    }
                    if (!checked) {
                        problems.add(label + " 沒有 2xx");
                    }
                }
            }
        }
        assertThat(problems).as("2xx 回應缺 content 或 schema 對不上回傳型別").isEmpty();
    }

    /** schema 名稱（springdoc 以泛型實參簡名串接）需包含回傳型別所有泛型實參的簡名 */
    private static boolean containsGenericNames(String ref, ResolvableType type) {
        for (ResolvableType generic : type.getGenerics()) {
            Class<?> raw = generic.resolve(Object.class);
            if (!ref.contains(raw.getSimpleName()) || !containsGenericNames(ref, generic)) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("所有 4xx／5xx 回應一律引用 ApiErrorResponse，不得引用成功 schema")
    void errorResponsesReferenceErrorSchemaOnly() throws Exception {
        JsonNode docs = fetchDocs();
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, JsonNode> path : docs.path("paths").properties()) {
            for (String method : HTTP_METHODS) {
                JsonNode op = path.getValue().path(method);
                for (Map.Entry<String, JsonNode> response : op.path("responses").properties()) {
                    String status = response.getKey();
                    if (!(status.startsWith("4") || status.startsWith("5"))) {
                        continue;
                    }
                    String label = method.toUpperCase() + " " + path.getKey() + " " + status;
                    if (ERROR_BODY_EXEMPTIONS.contains(label)) {
                        continue;
                    }
                    checked++;
                    String ref = response.getValue().path("content").path("*/*").path("schema").path("$ref").asText("");
                    if (!ERROR_SCHEMA_REF.equals(ref)) {
                        problems.add(label + " $ref=" + (ref.isEmpty() ? "(無)" : ref));
                    }
                }
            }
        }
        assertThat(checked).as("檢查的錯誤回應數").isGreaterThan(200);
        assertThat(problems).as("未引用 ApiErrorResponse 的錯誤回應").isEmpty();
    }

    @Test
    @DisplayName("components 註冊 ApiErrorResponse（code／message／data／timestamp），data 型別為 null")
    void errorSchemaIsRegistered() throws Exception {
        JsonNode schema = fetchDocs().path("components").path("schemas").path("ApiErrorResponse");
        assertThat(schema.isMissingNode()).as("ApiErrorResponse 已註冊").isFalse();
        assertThat(toList(schema.path("properties").fieldNames())).containsExactlyInAnyOrder("code", "message", "data", "timestamp");
        assertThat(schema.path("properties").path("data").path("type").asText()).isEqualTo("null");
    }

    @Test
    @DisplayName("抽查：reindex 202／409 保留成功 schema，PUT 文章 200 有 content 且 409 引用錯誤 schema")
    void spotCheckKeyEndpoints() throws Exception {
        JsonNode docs = fetchDocs();
        JsonNode reindex = operation(docs, "/api/v1/admin/search/reindex", "post").path("responses");
        assertThat(reindex.path("202").path("content").path("*/*").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiResponseSearchReindexJobResponse");
        assertThat(reindex.path("409").path("content").path("*/*").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiResponseSearchReindexJobResponse");
        JsonNode put = operation(docs, "/api/v1/articles/{uuid}", "put").path("responses");
        assertThat(put.path("200").path("content").path("*/*").path("schema").path("$ref").asText())
                .startsWith("#/components/schemas/ApiResponse");
        assertThat(put.path("409").path("content").path("*/*").path("schema").path("$ref").asText())
                .isEqualTo(ERROR_SCHEMA_REF);
        assertThat(put.path("409").path("description").asText()).contains("A0208");
    }
}
