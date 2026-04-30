package dowob.xyz.blog.common.constant;

/**
 * 全站密碼政策常數
 *
 * <p>統一定義密碼長度與複雜度要求，供所有密碼相關 DTO 的 validation annotation 引用。
 * 規則：最少 8 字元，且須包含至少一個英文字母及一個數字。</p>
 *
 * @author Yuan
 * @version 2.0
 */
public final class PasswordPolicy {

    /** 密碼最小長度 */
    public static final int MIN_LENGTH = 8;

    /** 密碼最大長度 */
    public static final int MAX_LENGTH = 50;

    /** 密碼複雜度 Regex：至少一個英文字母 + 至少一個數字 */
    public static final String PATTERN = "^(?=.*[A-Za-z])(?=.*\\d).{" + MIN_LENGTH + "," + MAX_LENGTH + "}$";

    /** 密碼 @Size 驗證失敗訊息（不再使用，保留向下相容） */
    public static final String SIZE_MESSAGE = "密碼須為 " + MIN_LENGTH + "-" + MAX_LENGTH + " 字元，且包含至少一個英文字母及一個數字";

    /** 密碼 @Pattern 驗證失敗訊息 */
    public static final String PATTERN_MESSAGE = "密碼須為 8-50 字元，且包含至少一個英文字母及一個數字";

    private PasswordPolicy() {
    }
}
