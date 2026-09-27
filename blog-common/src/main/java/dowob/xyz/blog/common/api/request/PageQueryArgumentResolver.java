package dowob.xyz.blog.common.api.request;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * {@link PageQuery} 的 controller 參數解析器。
 *
 * <p><b>存在理由</b>：分頁參數超出範圍必須回 400，但 {@code PageQuery} 的 compact constructor
 * 若在 Spring 的 record 建構子綁定中丟例外，會被包成 {@code BeanInstantiationException} 而成為 500。
 * 本解析器改由自己讀取本次請求的 {@code page} / {@code size}，並把所有驗證失敗統一轉成
 * {@link BusinessException}（{@link CommonErrorCode#PARAM_VALID_ERROR}），交由
 * {@code GlobalExceptionHandler} 回 400。</p>
 *
 * <p><b>驗證規則只有一份</b>：範圍檢查仍由 {@code PageQuery} 的 compact constructor 負責，
 * 本類別只補上「非整數」這個建構前才能判斷的情況，不另寫一份範圍判斷。</p>
 *
 * <p>controller 的簽章維持 {@code PageQuery pageQuery}，不需要任何註解；
 * 解析器由 {@code WebMvcConfig#addArgumentResolvers} 註冊，排在 Spring 內建的
 * model attribute 解析器之前，故會取代原本的 record 建構子綁定。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class PageQueryArgumentResolver implements HandlerMethodArgumentResolver {

    /** 頁碼的 query string 參數名 */
    private static final String PAGE_PARAM = "page";

    /** 每頁筆數的 query string 參數名 */
    private static final String SIZE_PARAM = "size";

    /**
     * 只處理型別為 {@link PageQuery} 的參數。
     *
     * @param parameter controller 方法參數
     * @return 是否由本解析器處理
     */
    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return PageQuery.class.equals(parameter.getParameterType());
    }

    /**
     * 解析並驗證本次請求的分頁參數。
     *
     * @param parameter     controller 方法參數
     * @param mavContainer  未使用
     * @param webRequest    本次請求
     * @param binderFactory 未使用
     * @return 已驗證的分頁參數
     * @throws BusinessException 參數非整數或超出合法範圍（→ 400）
     */
    @Override
    public PageQuery resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                     NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Integer page = parseInteger(webRequest, PAGE_PARAM);
        Integer size = parseInteger(webRequest, SIZE_PARAM);
        try {
            return new PageQuery(page, size);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR, e.getMessage());
        }
    }

    /**
     * 讀取整數 query string 參數；未提供或空白視為未提供。
     *
     * @param webRequest 本次請求
     * @param name       參數名
     * @return 參數值，未提供時為 {@code null}
     * @throws BusinessException 參數不是整數
     */
    private static Integer parseInteger(NativeWebRequest webRequest, String name) {
        String raw = webRequest.getParameter(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(CommonErrorCode.PARAM_VALID_ERROR, name + " 必須為整數");
        }
    }
}
