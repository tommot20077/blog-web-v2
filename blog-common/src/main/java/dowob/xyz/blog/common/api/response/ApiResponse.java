package dowob.xyz.blog.common.api.response;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import lombok.Data;

/**
 * 統一 API 響應結構
 *
 * @param <T> 數據類型
 * @author Yuan
 * @version 1.0
 */
@Data
public class ApiResponse<T> {
  /**
   * 狀態碼
   */
  private String code;

  /**
   * 響應訊息
   */
  private String message;

  /**
   * 數據對象
   */
  private T data;

  /**
   * 時間戳
   */
  private long timestamp;

  /**
   * 構造方法
   */
  protected ApiResponse() {
    this.timestamp = System.currentTimeMillis();
  }

  /**
   * 成功返回 (無數據)
   *
   * @param <T> 數據類型
   *
   * @return 響應對象
   */
  public static <T> ApiResponse<T> success() {
    return success(null);
  }

  /**
   * 成功返回 (帶數據)
   *
   * @param data 數據對象
   * @param <T>  數據類型
   *
   * @return 響應對象
   */
  public static <T> ApiResponse<T> success(T data) {
    ApiResponse<T> apiResponse = new ApiResponse<>();
    apiResponse.setCode(CommonErrorCode.SUCCESS.getCode());
    apiResponse.setMessage(CommonErrorCode.SUCCESS.getMessage());
    apiResponse.setData(data);
    return apiResponse;
  }

  /**
   * 成功返回 (帶數據與自定義訊息)
   *
   * @param data    數據對象
   * @param message 響應訊息
   * @param <T>     數據類型
   *
   * @return 響應對象
   */
  public static <T> ApiResponse<T> success(T data, String message) {
    ApiResponse<T> apiResponse = new ApiResponse<>();
    apiResponse.setCode(CommonErrorCode.SUCCESS.getCode());
    apiResponse.setMessage(message);
    apiResponse.setData(data);
    return apiResponse;
  }

  /**
   * 失敗返回 (使用標準錯誤碼)
   *
   * @param errorCode 錯誤碼接口
   * @param <T>       數據類型
   *
   * @return 響應對象
   */
  public static <T> ApiResponse<T> failed(IErrorCode errorCode) {
    ApiResponse<T> apiResponse = new ApiResponse<>();
    apiResponse.setCode(errorCode.getCode());
    apiResponse.setMessage(errorCode.getMessage());
    return apiResponse;
  }

  /**
   * 失敗返回 (自定義訊息)
   *
   * @param code    狀態碼
   * @param message 響應訊息
   * @param <T>     數據類型
   *
   * @return 響應對象
   */
  public static <T> ApiResponse<T> failed(String code, String message) {
    ApiResponse<T> apiResponse = new ApiResponse<>();
    apiResponse.setCode(code);
    apiResponse.setMessage(message);
    return apiResponse;
  }
}
