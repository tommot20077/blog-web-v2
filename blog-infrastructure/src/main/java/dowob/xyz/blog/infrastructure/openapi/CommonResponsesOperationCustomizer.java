package dowob.xyz.blog.infrastructure.openapi;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.method.HandlerMethod;

/**
 * 依 handler 上的標註，為 OpenAPI operation 統一補上共通錯誤回應，handler 不必逐一手寫。
 *
 * <ul>
 *   <li>帶 {@link PreAuthorize}：401（{@code A0005}）；表達式含 {@code hasAuthority／hasRole} 系列再加 403（{@code A0006}）。</li>
 *   <li>帶 {@link RateLimited}（方法或類別層）：429（{@code A0009}）與 {@code Retry-After} header 說明。</li>
 * </ul>
 *
 * <p>選用 customizer 而非手寫標註：共通錯誤由「實際生效的標註」推導，不會與程式行為漂移；
 * 已手寫同狀態碼的 {@code @ApiResponse} 時不覆蓋。500 由 {@link OpenApiConfig} 的全域 customizer 處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class CommonResponsesOperationCustomizer implements OperationCustomizer {

    /**
     * 補上共通錯誤回應
     *
     * @param operation     springdoc 產生的 operation
     * @param handlerMethod 對應的 handler
     * @return 補完後的 operation
     */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        PreAuthorize preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), PreAuthorize.class);
        if (preAuthorize == null) {
            preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), PreAuthorize.class);
        }
        if (preAuthorize != null) {
            addIfAbsent(operation, "401", CommonErrorCode.UNAUTHENTICATED.getCode() + " 未登入或 token 無效／過期");
            if (requiresAuthority(preAuthorize.value())) {
                addIfAbsent(operation, "403", CommonErrorCode.FORBIDDEN.getCode() + " 權限不足");
            }
        }
        if (isRateLimited(handlerMethod)) {
            ApiResponse tooMany = addIfAbsent(operation, "429",
                    CommonErrorCode.TOO_MANY_REQUESTS.getCode() + " 請求過於頻繁（應用層限流）");
            if (tooMany.getHeaders() == null || !tooMany.getHeaders().containsKey("Retry-After")) {
                tooMany.addHeaderObject("Retry-After", new Header()
                        .description("建議重試前等待的秒數（至少 1）")
                        .schema(new IntegerSchema()));
            }
        }
        return operation;
    }

    private static boolean isRateLimited(HandlerMethod handlerMethod) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), RateLimited.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), RateLimited.class);
    }

    private static boolean requiresAuthority(String expression) {
        return expression.contains("hasAuthority") || expression.contains("hasAnyAuthority")
                || expression.contains("hasRole") || expression.contains("hasAnyRole");
    }

    /** 該狀態碼尚無回應才新增；回傳最終存在的那一個 */
    private static ApiResponse addIfAbsent(Operation operation, String status, String description) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponse existing = operation.getResponses().get(status);
        if (existing != null) {
            return existing;
        }
        ApiResponse created = new ApiResponse().description(description);
        operation.getResponses().addApiResponse(status, created);
        return created;
    }
}
