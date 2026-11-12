package dowob.xyz.blog.infrastructure.openapi;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.HandlerMethod;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 統一整理 operation 的成功與錯誤回應 body，handler 不必逐一手寫 {@code @ApiResponse(responseCode = "200")}。
 *
 * <p>背景：springdoc 2.8.5 在方法上有 {@code @ApiResponse} 時只採用手寫的回應，不再依回傳型別補預設 200，
 * 且會把「回傳型別的成功 schema」填進手寫的 4xx 回應。結果是 70 個 operation 沒有成功回應、
 * 錯誤回應卻引用成功 schema（實際錯誤時 {@code data} 為 null）。本 customizer 修正兩者：</p>
 *
 * <ul>
 *   <li>operation 沒有任何 2xx／3xx 時補成功回應：狀態碼取自 {@code @ResponseStatus}（預設 200），
 *       description 為「成功」，content 沿用 springdoc 誤放在手寫錯誤回應上的成功 schema；回傳 {@code void} 不帶 content。</li>
 *   <li>手寫錯誤回應（4xx／5xx）的 content 改引用 {@value #ERROR_SCHEMA_NAME}；
 *       handler 的 {@code @ApiResponse} 明確指定 content 的狀態碼不覆蓋。</li>
 * </ul>
 *
 * <p>沒有 content 的 4xx／5xx（例如 {@link CommonResponsesOperationCustomizer} 補的 401／429、全域 500）
 * 由 {@code OpenApiConfig} 的全域 customizer 在所有 operation 處理完後補上同一個錯誤 schema，因此與兩個
 * operation customizer 的執行順序無關。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class ResponseBodyOperationCustomizer implements OperationCustomizer {

    /** 錯誤回應 schema 名稱（註冊於 components） */
    public static final String ERROR_SCHEMA_NAME = "ApiErrorResponse";

    /** 錯誤回應 content 使用的 media type，與 springdoc 推導的成功回應一致 */
    private static final String MEDIA_TYPE = "*/*";

    /** 成功回應的預設描述 */
    private static final String SUCCESS_DESCRIPTION = "成功";

    /**
     * 產生引用 {@value #ERROR_SCHEMA_NAME} 的 content
     *
     * @return 新的 content 物件（每次呼叫獨立，避免多個回應共用同一實例）
     */
    public static Content errorContent() {
        return new Content().addMediaType(MEDIA_TYPE,
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA_NAME)));
    }

    /**
     * 補齊成功回應並把手寫錯誤回應改引用錯誤 schema
     *
     * @param operation     springdoc 產生的 operation
     * @param handlerMethod 對應的 handler
     * @return 處理後的 operation
     */
    @Override
    public Operation customize(Operation operation, HandlerMethod handlerMethod) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        ApiResponses responses = operation.getResponses();
        Set<String> explicitContentStatuses = explicitContentStatuses(handlerMethod);

        Content successContent = null;
        boolean hasSuccess = false;
        for (Map.Entry<String, ApiResponse> entry : responses.entrySet()) {
            String status = entry.getKey();
            ApiResponse response = entry.getValue();
            if (status.startsWith("2") || status.startsWith("3")) {
                hasSuccess = true;
            } else if (isError(status) && !explicitContentStatuses.contains(status) && response.getContent() != null) {
                if (successContent == null) {
                    successContent = copyOf(response.getContent());
                }
                response.setContent(errorContent());
            }
        }

        if (!hasSuccess) {
            ApiResponse success = new ApiResponse().description(SUCCESS_DESCRIPTION);
            if (!returnsVoid(handlerMethod) && successContent != null) {
                success.setContent(successContent);
            }
            responses.addApiResponse(successStatus(handlerMethod), success);
        }
        return operation;
    }

    private static boolean isError(String status) {
        return status.startsWith("4") || status.startsWith("5");
    }

    private static boolean returnsVoid(HandlerMethod handlerMethod) {
        return handlerMethod.getMethod().getReturnType() == void.class;
    }

    /** 成功狀態碼：{@code @ResponseStatus} 宣告者，否則 200 */
    private static String successStatus(HandlerMethod handlerMethod) {
        ResponseStatus responseStatus =
                AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), ResponseStatus.class);
        return responseStatus == null ? "200" : String.valueOf(responseStatus.code().value());
    }

    /** handler 上 {@code @ApiResponse} 明確指定 content 的狀態碼 */
    private static Set<String> explicitContentStatuses(HandlerMethod handlerMethod) {
        Set<String> statuses = new HashSet<>();
        for (io.swagger.v3.oas.annotations.responses.ApiResponse annotation
                : handlerMethod.getMethod().getAnnotationsByType(io.swagger.v3.oas.annotations.responses.ApiResponse.class)) {
            if (annotation.content().length > 0) {
                statuses.add(annotation.responseCode());
            }
        }
        return statuses;
    }

    /** 複製 content 的 media type 與 schema 參照，避免之後修改某個回應時牽動另一個 */
    private static Content copyOf(Content source) {
        Content copy = new Content();
        source.forEach((mediaType, value) -> {
            Schema<?> schema = value.getSchema();
            Schema<?> copiedSchema = schema != null && schema.get$ref() != null ? new Schema<>().$ref(schema.get$ref()) : schema;
            copy.addMediaType(mediaType, new MediaType().schema(copiedSchema));
        });
        return copy;
    }
}
