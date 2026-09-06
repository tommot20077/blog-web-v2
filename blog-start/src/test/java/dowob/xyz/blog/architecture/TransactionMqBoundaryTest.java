package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction ＋ MQ 時序守衛（{@code code-standards.md} §「Transaction + MQ 時序」）。
 *
 * <p>守衛 #6：{@code @Transactional} 涵蓋的方法內不得直接呼叫
 * {@code RabbitTemplate.convertAndSend}。DB 尚未 commit 就送出訊息，
 * 一旦交易回滾便產生「MQ 說發生了、DB 說沒發生」的不一致——這是
 * BUG-2026-001 FIN-2 的 6 處缺陷，正解是 {@code TransactionTemplate}
 * 縮小交易範圍、commit 後再以 best-effort 發送。</p>
 *
 * <p><b>本守衛的界限（務必先讀再依賴它）</b>：ArchUnit 分析的是<b>直接</b>方法呼叫。
 * 若交易方法呼叫的是 {@code ArticleEventPublisher} 這類封裝層、由該層再發 MQ，
 * 本守衛<b>看不到</b>。它擋的是「退回 BUG-2026-001 當時那種直接呼叫」的寫法，
 * 不是「交易內絕不可能有 MQ 副作用」的完整證明。</p>
 *
 * <p><b>為何附反向驗證</b>：守衛掃不到違規有兩種可能——真的沒有違規，或守衛壞了。
 * 只斷言 violations 為空無法區分（見 {@code 29a1000} 堵住守衛 #5 兩處靜默假陰性）。
 * 故判定邏輯抽為 {@link #findViolations(JavaClasses)}，另以故意違規的 fixture
 * 證明它確實抓得到，且涵蓋方法層與類層兩種標註形式。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class TransactionMqBoundaryTest {

    /** Spring 交易標註的全限定名 */
    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    /** MQ 樣板型別的簡單名稱 */
    private static final String RABBIT_TEMPLATE = "RabbitTemplate";

    /** MQ 發送方法的名稱前綴（涵蓋 convertAndSend 的各多載與 convertSendAndReceive） */
    private static final String SEND_PREFIX = "convert";

    @Test
    @DisplayName("守衛 #6：@Transactional 涵蓋的方法內不得直接發送 MQ")
    void transactionalMethodMustNotSendMqDirectly() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog.module");

        assertThat(findViolations(classes))
                .as("@Transactional 內不得直接 rabbitTemplate.convertAndSend——"
                  + "DB 未 commit 即發送，回滾後會留下無對應資料的訊息（BUG-2026-001 FIN-2）。"
                  + "改用 TransactionTemplate 縮小交易範圍，commit 後再 best-effort 發送。")
                .isEmpty();
    }

    @Test
    @DisplayName("反向驗證：守衛抓得到方法層與類層兩種 @Transactional 標註形式")
    void guardDetectsBothMethodLevelAndClassLevelTransactional() {
        JavaClasses fixtures = new ClassFileImporter()
                .importPackages("dowob.xyz.blog.architecture.fixture");

        List<String> violations = findViolations(fixtures);

        assertThat(violations)
                .as("守衛必須同時抓到方法層與類層標註；漏掉任一種即為靜默假陰性")
                .anySatisfy(v -> assertThat(v).contains("methodLevelTransactionalSendsMq"))
                .anySatisfy(v -> assertThat(v).contains("inheritsClassLevelTransactionalAndSendsMq"));
    }

    @Test
    @DisplayName("反向驗證：守衛不誤判「無交易標註而發 MQ」與「有交易標註但不發 MQ」")
    void guardDoesNotFlagLegitimatePatterns() {
        JavaClasses fixtures = new ClassFileImporter()
                .importPackages("dowob.xyz.blog.architecture.fixture");

        List<String> violations = findViolations(fixtures);

        assertThat(violations)
                .as("這兩種形式合規，被抓到即為假陽性")
                .noneMatch(v -> v.contains("nonTransactionalSendsMq"))
                .noneMatch(v -> v.contains("transactionalWithoutMq"));
    }

    /**
     * 找出所有「在交易涵蓋範圍內直接發送 MQ」的方法。
     *
     * @param classes 待掃描的類集合
     * @return 違規方法的 {@code 類別#方法} 描述，無違規則為空清單
     */
    private static List<String> findViolations(JavaClasses classes) {
        return classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(TransactionMqBoundaryTest::isCoveredByTransaction)
                .filter(TransactionMqBoundaryTest::sendsMqDirectly)
                .map(m -> m.getOwner().getName() + "#" + m.getName())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判斷方法是否落在交易涵蓋範圍內。
     *
     * <p>方法層標註與<b>類層</b>標註都算——類層 {@code @Transactional} 會套用到所有
     * public 方法，只看方法層會對 BUG-2026-001 當時的實際寫法之一失明。</p>
     *
     * @param method 待判斷的方法
     * @return 落在交易範圍內回傳 true
     */
    private static boolean isCoveredByTransaction(JavaMethod method) {
        JavaClass owner = method.getOwner();
        return method.isAnnotatedWith(TRANSACTIONAL) || owner.isAnnotatedWith(TRANSACTIONAL);
    }

    /**
     * 判斷方法內是否直接呼叫 MQ 發送。
     *
     * @param method 待判斷的方法
     * @return 直接呼叫 RabbitTemplate 的發送方法回傳 true
     */
    private static boolean sendsMqDirectly(JavaMethod method) {
        return method.getMethodCallsFromSelf().stream()
                .anyMatch(call -> call.getTargetOwner().getSimpleName().equals(RABBIT_TEMPLATE)
                               && call.getName().startsWith(SEND_PREFIX));
    }
}
