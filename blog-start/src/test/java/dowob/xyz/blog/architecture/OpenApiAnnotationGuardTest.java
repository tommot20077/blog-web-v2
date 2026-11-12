package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 標註守衛（W7-C2／ARCH-14）：springdoc 產生的 openapi.json 是前後端契約的唯一真相，
 * 因此每個 handler 都必須有 {@code @Operation(summary)}，每個 controller 都必須有 {@code @Tag}；
 * 以 {@code @ResponseStatus} 宣告非 200 成功碼的 handler 必須有對應的 {@code @ApiResponse}。
 *
 * <p>寫法參考 {@link RateLimitedEndpointGuardTest}。回傳 {@code ResponseEntity}、成功碼要看執行期的 handler 無法靜態判斷，
 * 由 {@code OpenApiDocsE2E} 以實際產出的 openapi.json 補驗（例如 reindex 的 202／409）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("OpenAPI 標註守衛（C2）")
class OpenApiAnnotationGuardTest {

    private static JavaClasses importProduction() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog");
    }

    private static JavaClasses importFixtures() {
        return new ClassFileImporter().importPackages("dowob.xyz.blog.architecture.fixture.openapi");
    }

    /** 帶任何 MVC 映射標註的方法（含 {@code @RequestMapping(method=...)}） */
    private static boolean isHandler(JavaMethod method) {
        return method.getAnnotations().stream().anyMatch(a -> a.getRawType().getSimpleName().endsWith("Mapping"));
    }

    private static String key(JavaMethod method) {
        return method.getOwner().getSimpleName() + "#" + method.getName();
    }

    /** 缺 {@code @Operation} 或 summary 空白的 handler */
    private static Set<String> handlersWithoutSummary(JavaClasses classes) {
        Set<String> result = new TreeSet<>();
        for (JavaMethod method : classes.stream().flatMap(c -> c.getMethods().stream()).toList()) {
            if (!isHandler(method)) {
                continue;
            }
            Operation op = method.reflect().getAnnotation(Operation.class);
            if (op == null || op.summary().isBlank()) {
                result.add(key(method));
            }
        }
        return result;
    }

    /** 宣告 {@code @ResponseStatus} 非 200 卻沒有對應 {@code @ApiResponse(responseCode)} 的 handler */
    private static Set<String> handlersWithUndocumentedStatus(JavaClasses classes) {
        Set<String> result = new TreeSet<>();
        for (JavaMethod method : classes.stream().flatMap(c -> c.getMethods().stream()).toList()) {
            ResponseStatus rs = isHandler(method) ? method.reflect().getAnnotation(ResponseStatus.class) : null;
            if (rs == null) {
                continue;
            }
            String code = String.valueOf(rs.value().value());
            boolean documented = documentedCodes(method).contains(code);
            if (!"200".equals(code) && !documented) {
                result.add(key(method));
            }
        }
        return result;
    }

    private static Set<String> documentedCodes(JavaMethod method) {
        Set<String> codes = new TreeSet<>();
        for (ApiResponse r : method.reflect().getAnnotationsByType(ApiResponse.class)) {
            codes.add(r.responseCode());
        }
        ApiResponses container = method.reflect().getAnnotation(ApiResponses.class);
        if (container != null) {
            Arrays.stream(container.value()).forEach(r -> codes.add(r.responseCode()));
        }
        return codes;
    }

    /** 含 handler 卻沒有（或空白）{@code @Tag(name)} 的 controller */
    private static Set<String> controllersWithoutTag(JavaClasses classes) {
        Set<String> result = new TreeSet<>();
        for (JavaClass clazz : classes) {
            boolean hasHandler = clazz.getMethods().stream().anyMatch(OpenApiAnnotationGuardTest::isHandler);
            if (!hasHandler) {
                continue;
            }
            Tag tag = clazz.reflect().getAnnotation(Tag.class);
            if (tag == null || tag.name().isBlank()) {
                result.add(clazz.getSimpleName());
            }
        }
        return result;
    }

    @Test
    @DisplayName("所有 handler 必須帶 @Operation，且 summary 不為空白")
    void everyHandlerMustHaveOperationSummary() {
        assertThat(handlersWithoutSummary(importProduction()))
                .as("以下 handler 缺少 @Operation(summary=...)。openapi.json 是契約唯一真相，"
                        + "請依 code-standards.md「OpenAPI 標註慣例」補上")
                .isEmpty();
    }

    @Test
    @DisplayName("以 @ResponseStatus 宣告非 200 成功碼的 handler 必須有對應 @ApiResponse")
    void nonOkResponseStatusMustBeDocumented() {
        assertThat(handlersWithUndocumentedStatus(importProduction())).isEmpty();
    }

    @Test
    @DisplayName("每個 controller 必須有 @Tag(name)")
    void everyControllerMustHaveTag() {
        assertThat(controllersWithoutTag(importProduction()))
                .as("以下 controller 缺少 @Tag(name = 模組中文名)")
                .isEmpty();
    }

    @Test
    @DisplayName("反向驗證：守衛抓得到缺 @Operation、summary 空白，且不誤判已標註與非 handler")
    void guardDetectsMissingOperation() {
        assertThat(handlersWithoutSummary(importFixtures()))
                .containsExactlyInAnyOrder(
                        "OpenApiAnnotationFixture#missingGet",
                        "OpenApiAnnotationFixture#missingPost",
                        "OpenApiAnnotationFixture#missingPut",
                        "OpenApiAnnotationFixture#missingPatch",
                        "OpenApiAnnotationFixture#missingDelete",
                        "OpenApiAnnotationFixture#missingRequestMapping",
                        "OpenApiAnnotationFixture#blankSummary");
    }

    @Test
    @DisplayName("反向驗證：守衛抓得到 @ResponseStatus 無對應 @ApiResponse，且不誤判已標註者")
    void guardDetectsUndocumentedStatus() {
        assertThat(handlersWithUndocumentedStatus(importFixtures()))
                .containsExactly("OpenApiAnnotationFixture#statusWithoutApiResponse");
    }

    @Test
    @DisplayName("反向驗證：守衛抓得到缺 @Tag 的 controller")
    void guardDetectsMissingTag() {
        assertThat(controllersWithoutTag(importFixtures())).containsExactly("NoTag");
    }
}
