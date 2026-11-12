package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dowob.xyz.blog.common.api.errorcode.IErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 錯誤碼集中管理守衛（W7-C3）。
 *
 * <p>錯誤碼字串屬前端契約。規則：所有實作 {@link IErrorCode} 的 enum 必須位於
 * {@code dowob.xyz.blog.common.api.errorcode}（blog-common），且全站錯誤碼字串不可重複。
 * 掃描範圍為 main classpath（排除 test-classes，故本類別內的反向驗證 fixture 不會污染正式掃描）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class ErrorCodeCentralizationTest {

    /** 錯誤碼 enum 唯一允許的 package */
    private static final String ERROR_CODE_PACKAGE = "dowob.xyz.blog.common.api.errorcode";

    /** 反向驗證用：錯誤位置的 enum */
    enum MisplacedFixture implements IErrorCode {
        DUP("Z0001", "a");

        private final String code;
        private final String message;

        MisplacedFixture(String code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }

    /** 反向驗證用：與 {@link MisplacedFixture} 重複的錯誤碼 */
    enum DuplicateFixture implements IErrorCode {
        DUP("Z0001", "b");

        private final String code;
        private final String message;

        DuplicateFixture(String code, String message) {
            this.code = code;
            this.message = message;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getMessage() {
            return message;
        }
    }

    @Test
    @DisplayName("所有實作 IErrorCode 的 enum 必須位於 blog-common 的 api.errorcode package")
    void allErrorCodeEnumsMustLiveInCommonErrorCodePackage() {
        assertThat(findMisplaced(productionErrorCodeEnums()))
                .as("錯誤碼一律放 blog-common 的 api/errorcode/，不得留在各模組內")
                .isEmpty();
    }

    @Test
    @DisplayName("防空轉：正式掃描必須找到既有的錯誤碼 enum 與錯誤碼")
    void productionScanMustFindErrorCodeEnums() {
        List<Class<?>> enums = productionErrorCodeEnums();

        assertThat(enums).extracting(Class::getSimpleName)
                .contains("CommonErrorCode", "UserErrorCode", "ArticleErrorCode");
        assertThat(allCodes(enums)).hasSizeGreaterThan(50);
    }

    @Test
    @DisplayName("全站錯誤碼字串不可重複")
    void errorCodeStringsMustBeGloballyUnique() {
        assertThat(findDuplicates(productionErrorCodeEnums()))
                .as("錯誤碼字串屬前端契約，不同 enum 常數不得共用同一字串")
                .isEmpty();
    }

    @Test
    @DisplayName("反向驗證：位置錯誤與字串重複都抓得到")
    void guardDetectsMisplacedAndDuplicateCodes() {
        List<Class<?>> fixtures = List.of(MisplacedFixture.class, DuplicateFixture.class);

        assertThat(findMisplaced(fixtures)).hasSize(2);
        assertThat(findDuplicates(fixtures)).hasSize(1).first().asString().contains("Z0001");
    }

    // ─── 判定邏輯 ───

    private static List<String> findMisplaced(List<Class<?>> enums) {
        return enums.stream()
                .filter(c -> !c.getPackageName().equals(ERROR_CODE_PACKAGE))
                .map(Class::getName)
                .sorted()
                .toList();
    }

    private static List<String> findDuplicates(List<Class<?>> enums) {
        Map<String, List<String>> owners = new HashMap<>();
        for (Class<?> type : enums) {
            for (Object constant : type.getEnumConstants()) {
                IErrorCode errorCode = (IErrorCode) constant;
                owners.computeIfAbsent(errorCode.getCode(), k -> new ArrayList<>())
                        .add(type.getSimpleName() + "." + ((Enum<?>) constant).name());
            }
        }
        return owners.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " <- " + e.getValue())
                .sorted()
                .toList();
    }

    private static List<String> allCodes(List<Class<?>> enums) {
        List<String> codes = new ArrayList<>();
        for (Class<?> type : enums) {
            for (Object constant : type.getEnumConstants()) {
                codes.add(((IErrorCode) constant).getCode());
            }
        }
        return codes;
    }

    /** 掃描 main classpath 中所有實作 IErrorCode 的 enum。 */
    private static List<Class<?>> productionErrorCodeEnums() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog");
        List<Class<?>> result = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (javaClass.isEnum() && javaClass.isAssignableTo(IErrorCode.class)) {
                result.add(javaClass.reflect());
            }
        }
        return result;
    }
}
