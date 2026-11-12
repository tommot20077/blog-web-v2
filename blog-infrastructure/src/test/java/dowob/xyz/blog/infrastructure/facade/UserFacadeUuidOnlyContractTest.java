package dowob.xyz.blog.infrastructure.facade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M8：{@link UserFacade} 全面 UUID 的契約測試。
 *
 * <p>他模組已全部改讀 {@code AuthUser.uuid()}，不再以 Long 使用者 id 呼叫 user 模組；
 * owner 直接從介面移除 Long 版方法（{@code getUserUuidById}、{@code getUserNicknameById}、
 * {@code getUserUsernameById}、{@code getAuthorInfoByIds}），不保留為過渡方法。
 * 本測試以反射掃描介面的每個方法簽章（參數與回傳型別，含泛型引數），任何 {@code Long}／{@code long}
 * 的出現都視為違規，防止之後有人又加回 Long 版。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("UserFacade 全面 UUID（M8：Long 版方法已移除）")
class UserFacadeUuidOnlyContractTest {

    /** 已移除的 Long 版方法名稱 */
    private static final List<String> REMOVED_METHODS = List.of(
            "getUserUuidById", "getUserNicknameById", "getUserUsernameById", "getAuthorInfoByIds");

    /**
     * 判斷型別（含泛型引數）是否出現 Long／long。
     *
     * @param type 反射取得的型別
     * @return 出現 Long 或 long 時為 true
     */
    private static boolean mentionsLong(Type type) {
        if (type == Long.class || type == long.class) {
            return true;
        }
        if (type instanceof ParameterizedType parameterized) {
            return Arrays.stream(parameterized.getActualTypeArguments()).anyMatch(
                    UserFacadeUuidOnlyContractTest::mentionsLong);
        }
        return false;
    }

    @Test
    @DisplayName("介面不再宣告 getUserUuidById／getUserNicknameById／getUserUsernameById／getAuthorInfoByIds")
    void interface_hasNoLongBasedMethodsAnymore() {
        List<String> declared = Arrays.stream(UserFacade.class.getDeclaredMethods()).map(Method::getName).toList();

        assertThat(declared).doesNotContainAnyElementsOf(REMOVED_METHODS);
    }

    @Test
    @DisplayName("介面所有方法的參數與回傳型別（含泛型引數）都不含 Long／long")
    void everyMethodSignature_mentionsNoLong() {
        assertThat(UserFacade.class.getDeclaredMethods())
                .allSatisfy(method -> {
                    assertThat(Arrays.stream(method.getGenericParameterTypes()).anyMatch(
                            UserFacadeUuidOnlyContractTest::mentionsLong))
                            .as("參數含 Long：%s", method).isFalse();
                    assertThat(mentionsLong(method.getGenericReturnType()))
                            .as("回傳含 Long：%s", method).isFalse();
                });
    }

    @Test
    @DisplayName("保留以 UUID 為鍵的 getDisplayInfoByUuids（防假陰性：介面不是空的）")
    void interface_stillDeclaresUuidBasedDisplayInfoLookup() {
        assertThat(Arrays.stream(UserFacade.class.getDeclaredMethods()).map(Method::getName))
                .contains("getDisplayInfoByUuids");
    }
}
