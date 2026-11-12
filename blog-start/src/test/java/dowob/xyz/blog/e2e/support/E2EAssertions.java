package dowob.xyz.blog.e2e.support;

import org.springframework.test.web.servlet.ResultMatcher;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * E2E 測試用 ApiResponse 斷言工具
 */
public final class E2EAssertions {

    private E2EAssertions() {
    }

    /**
     * 斷言 API 回應成功（code = "00000"）
     */
    public static ResultMatcher apiSuccess() {
        return jsonPath("$.code").value("00000");
    }

    /**
     * 斷言 API 回應特定錯誤碼
     */
    public static ResultMatcher apiError(String errorCode) {
        return jsonPath("$.code").value(errorCode);
    }

    /**
     * 斷言 API 回應 data 欄位存在
     */
    public static ResultMatcher hasData() {
        return jsonPath("$.data").exists();
    }

    /**
     * 斷言 API 回應 data 欄位為 null
     */
    public static ResultMatcher noData() {
        return jsonPath("$.data").doesNotExist();
    }
}
