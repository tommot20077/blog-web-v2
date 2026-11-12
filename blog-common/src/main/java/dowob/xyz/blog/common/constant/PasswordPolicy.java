package dowob.xyz.blog.common.constant;

/**
 * 全站密碼政策常數
 *
 * <p>統一定義密碼長度與複雜度要求，供所有密碼相關 DTO 的 validation annotation 引用。</p>
 *
 * <p><strong>複雜度規則</strong>：密碼長度須為 {@value #MIN_LENGTH}-{@value #MAX_LENGTH} 字元，
 * 且同時包含下列四類字元各至少一個：</p>
 * <ul>
 *     <li>小寫英文字母（a-z）</li>
 *     <li>大寫英文字母（A-Z）</li>
 *     <li>數字（0-9）</li>
 *     <li>特殊字元（{@value #SPECIAL_CHARS}）</li>
 * </ul>
 *
 * <p>規則同時透過 lookahead 與字元集合限制：密碼僅允許英數字與上述特殊字元，
 * 不接受集合外的符號（如空白、{@code ^}、{@code (} 等），以維持 regex 與文案的一致性。</p>
 *
 * @author Yuan
 * @version 3.0
 */
public final class PasswordPolicy {

    /** 密碼最小長度 */
    public static final int MIN_LENGTH = 8;

    /** 密碼最大長度 */
    public static final int MAX_LENGTH = 50;

    /** 允許的特殊字元集合（regex 與訊息文案共用，須保持一致） */
    public static final String SPECIAL_CHARS = "@$!%*?&#";

    /**
     * 密碼複雜度 Regex。
     *
     * <p>要求：至少一個小寫字母、一個大寫字母、一個數字、一個特殊字元（{@value #SPECIAL_CHARS}），
     * 且整體僅由英數字與允許的特殊字元組成，長度 {@value #MIN_LENGTH}-{@value #MAX_LENGTH}。</p>
     */
    public static final String PATTERN =
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#])[A-Za-z\\d@$!%*?&#]{"
                    + MIN_LENGTH + "," + MAX_LENGTH + "}$";

    /** 密碼 @Size 驗證失敗訊息（不再使用，保留向下相容） */
    public static final String SIZE_MESSAGE =
            "密碼須為 " + MIN_LENGTH + "-" + MAX_LENGTH + " 字元，且須包含小寫字母、大寫字母、數字及特殊字元（" + SPECIAL_CHARS + "）";

    /** 密碼 @Pattern 驗證失敗訊息 */
    public static final String PATTERN_MESSAGE =
            "密碼須為 " + MIN_LENGTH + "-" + MAX_LENGTH + " 字元，且須包含小寫字母、大寫字母、數字及特殊字元（" + SPECIAL_CHARS + "）";

    private PasswordPolicy() {
    }
}
