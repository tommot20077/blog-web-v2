package dowob.xyz.blog.common.api.response;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.Data;

/**
 * 統一 API 響應結構
 * @param <T> 數據類型
 */
@Data
public class ApiResponse<T> {
    private String code;
    private String message;
    private T data;
    private long timestamp;

    protected ApiResponse() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 成功返回 (無數據)
     */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /**
     * 成功返回 (帶數據)
     */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.setCode(CommonErrorCode.SUCCESS.getCode());
        apiResponse.setMessage(CommonErrorCode.SUCCESS.getMessage());
        apiResponse.setData(data);
        return apiResponse;
    }

    /**
     * 失敗返回 (使用標準錯誤碼)
     */
    public static <T> ApiResponse<T> failed(IErrorCode errorCode) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.setCode(errorCode.getCode());
        apiResponse.setMessage(errorCode.getMessage());
        return apiResponse;
    }

    /**
     * 失敗返回 (自定義訊息)
     */
    public static <T> ApiResponse<T> failed(String code, String message) {
        ApiResponse<T> apiResponse = new ApiResponse<>();
        apiResponse.setCode(code);
        apiResponse.setMessage(message);
        return apiResponse;
    }
}