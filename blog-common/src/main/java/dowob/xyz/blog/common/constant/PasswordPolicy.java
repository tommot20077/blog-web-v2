package dowob.xyz.blog.common.constant;

/**
 * 全站密碼政策常數
 *
 * <p>統一定義密碼長度限制，供所有密碼相關 DTO 的 {@code @Size} 驗證引用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class PasswordPolicy {

    /** 密碼最小長度 */
    public static final int MIN_LENGTH = 6;

    /** 密碼最大長度 */
    public static final int MAX_LENGTH = 50;

    /** 密碼長度驗證失敗訊息 */
    public static final String SIZE_MESSAGE = "密碼長度須為 " + MIN_LENGTH + "-" + MAX_LENGTH + " 字元";

    private PasswordPolicy() {
    }
}
