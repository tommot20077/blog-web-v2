package dowob.xyz.blog.common.api.response;

import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.TagErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiResponse 靜態工廠方法單元測試
 *
 * <p>驗證 {@code success()}、{@code failed(IErrorCode)}、{@code failed(String, String)} 的欄位行為。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ApiResponse 靜態工廠方法測試")
class ApiResponseTest {

    private static final String SUCCESS_CODE = "00000";
    private static final String SUCCESS_MESSAGE = "操作成功";

    @Nested
    @DisplayName("success() 無參數工廠方法測試")
    class SuccessNoArgTest {

        @Test
        @DisplayName("success() code 應為 '00000'")
        void success_noArg_codeIsSuccess() {
            ApiResponse<Void> response = ApiResponse.success();
            assertThat(response.getCode()).isEqualTo(SUCCESS_CODE);
        }

        @Test
        @DisplayName("success() message 應為 '操作成功'")
        void success_noArg_messageIsSuccess() {
            ApiResponse<Void> response = ApiResponse.success();
            assertThat(response.getMessage()).isEqualTo(SUCCESS_MESSAGE);
        }

        @Test
        @DisplayName("success() data 應為 null")
        void success_noArg_dataShouldBeNull() {
            ApiResponse<Void> response = ApiResponse.success();
            assertThat(response.getData()).isNull();
        }

        @Test
        @DisplayName("success() timestamp 應大於 0")
        void success_noArg_timestampIsPositive() {
            ApiResponse<Void> response = ApiResponse.success();
            assertThat(response.getTimestamp()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("success(T data) 帶資料工廠方法測試")
    class SuccessWithDataTest {

        @Test
        @DisplayName("success(data) code 應為 '00000'")
        void success_withData_codeIsSuccess() {
            ApiResponse<String> response = ApiResponse.success("payload");
            assertThat(response.getCode()).isEqualTo(SUCCESS_CODE);
        }

        @Test
        @DisplayName("success(data) data 應與傳入值相同")
        void success_withData_dataShouldMatch() {
            String payload = "hello";
            ApiResponse<String> response = ApiResponse.success(payload);
            assertThat(response.getData()).isEqualTo(payload);
        }

        @Test
        @DisplayName("success(data) message 應為 '操作成功'")
        void success_withData_messageIsSuccess() {
            ApiResponse<Integer> response = ApiResponse.success(42);
            assertThat(response.getMessage()).isEqualTo(SUCCESS_MESSAGE);
        }

        @Test
        @DisplayName("success(null) data 應為 null，code 仍為 '00000'")
        void success_withNullData_dataIsNullCodeIsSuccess() {
            ApiResponse<String> response = ApiResponse.success(null);
            assertThat(response.getData()).isNull();
            assertThat(response.getCode()).isEqualTo(SUCCESS_CODE);
        }
    }

    @Nested
    @DisplayName("success(T data, String message) 自訂訊息工廠方法測試")
    class SuccessWithCustomMessageTest {

        @Test
        @DisplayName("success(data, message) code 應為 '00000'")
        void success_withCustomMessage_codeIsSuccess() {
            ApiResponse<String> response = ApiResponse.success("data", "自訂訊息");
            assertThat(response.getCode()).isEqualTo(SUCCESS_CODE);
        }

        @Test
        @DisplayName("success(data, message) message 應與傳入值相同")
        void success_withCustomMessage_messageMatches() {
            String customMsg = "文章已建立";
            ApiResponse<String> response = ApiResponse.success("data", customMsg);
            assertThat(response.getMessage()).isEqualTo(customMsg);
        }

        @Test
        @DisplayName("success(data, message) data 應與傳入值相同")
        void success_withCustomMessage_dataMatches() {
            ApiResponse<Integer> response = ApiResponse.success(99, "自訂訊息");
            assertThat(response.getData()).isEqualTo(99);
        }
    }

    @Nested
    @DisplayName("failed(IErrorCode) 工廠方法測試")
    class FailedWithErrorCodeTest {

        @Test
        @DisplayName("failed(ArticleErrorCode.ARTICLE_NOT_FOUND) code 應為 'A0201'")
        void failed_articleNotFound_codeIsA0201() {
            ApiResponse<Void> response = ApiResponse.failed(ArticleErrorCode.ARTICLE_NOT_FOUND);
            assertThat(response.getCode()).isEqualTo("A0201");
        }

        @Test
        @DisplayName("failed(ArticleErrorCode.ARTICLE_NOT_FOUND) message 應不為空")
        void failed_articleNotFound_messageIsNotBlank() {
            ApiResponse<Void> response = ApiResponse.failed(ArticleErrorCode.ARTICLE_NOT_FOUND);
            assertThat(response.getMessage()).isNotBlank();
        }

        @Test
        @DisplayName("failed(TagErrorCode.TAG_NOT_FOUND) code 應為 'A0301'")
        void failed_tagNotFound_codeIsA0301() {
            ApiResponse<Void> response = ApiResponse.failed(TagErrorCode.TAG_NOT_FOUND);
            assertThat(response.getCode()).isEqualTo("A0301");
        }

        @Test
        @DisplayName("failed(IErrorCode) data 應為 null")
        void failed_withErrorCode_dataShouldBeNull() {
            ApiResponse<Void> response = ApiResponse.failed(ArticleErrorCode.ARTICLE_ACCESS_DENIED);
            assertThat(response.getData()).isNull();
        }

        @Test
        @DisplayName("failed(IErrorCode) timestamp 應大於 0")
        void failed_withErrorCode_timestampIsPositive() {
            ApiResponse<Void> response = ApiResponse.failed(CommonErrorCode.SYSTEM_EXECUTION_ERROR);
            assertThat(response.getTimestamp()).isGreaterThan(0);
        }

        @Test
        @DisplayName("failed(IErrorCode) code 與 message 應精確符合 errorCode 欄位")
        void failed_withErrorCode_codeAndMessageMatchErrorCode() {
            ArticleErrorCode target = ArticleErrorCode.ARTICLE_STATUS_TRANSITION_INVALID;
            ApiResponse<Void> response = ApiResponse.failed(target);
            assertThat(response.getCode()).isEqualTo(target.getCode());
            assertThat(response.getMessage()).isEqualTo(target.getMessage());
        }
    }

    @Nested
    @DisplayName("failed(String code, String message) 自訂錯誤工廠方法測試")
    class FailedWithCustomCodeTest {

        @Test
        @DisplayName("failed(code, message) code 應與傳入值相同")
        void failed_customCodeMessage_codeMatches() {
            ApiResponse<Void> response = ApiResponse.failed("X9999", "自訂錯誤");
            assertThat(response.getCode()).isEqualTo("X9999");
        }

        @Test
        @DisplayName("failed(code, message) message 應與傳入值相同")
        void failed_customCodeMessage_messageMatches() {
            ApiResponse<Void> response = ApiResponse.failed("X9999", "自訂錯誤訊息");
            assertThat(response.getMessage()).isEqualTo("自訂錯誤訊息");
        }

        @Test
        @DisplayName("failed(code, message) data 應為 null")
        void failed_customCodeMessage_dataShouldBeNull() {
            ApiResponse<Void> response = ApiResponse.failed("E0001", "錯誤");
            assertThat(response.getData()).isNull();
        }
    }
}
