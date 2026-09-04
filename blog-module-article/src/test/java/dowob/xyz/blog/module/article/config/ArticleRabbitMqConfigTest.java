package dowob.xyz.blog.module.article.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleRabbitMqConfig 單元測試
 *
 * <p>
 * 守衛測試：斷言本模組宣告的每個 Queue 都有對應的 {@code @RabbitListener} consumer，
 * 防止「有 Bean 宣告、有 Binding、有訊息進去，但沒有任何 consumer」的孤兒 queue 再次出現。
 * 本案例源自 {@code article.published} 孤兒 queue（ARCH-05／PERF-14）：該 queue 曾被本模組
 * 宣告並綁定，但實際消費者在 search／recommend 模組各自宣告的 queue 上，本模組從未消費，
 * 導致 durable queue 攜帶完整文章正文無限堆積，最終撞上 broker flow control 阻塞所有 publisher。
 * </p>
 *
 * <p>
 * 守衛範圍僅限本模組：驗證「本模組宣告的 Queue」在「本模組的類別路徑」中都能找到對應的
 * {@code @RabbitListener}。這對齊目前全站的既有慣例——每個 {@code XxxRabbitMqConfig}
 * 宣告的 Queue 都由同一模組內的 Consumer 消費（search／recommend／user／version／series／tag
 * 模組皆是如此），跨模組共用的是 routing key 而非 queue 本身。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleRabbitMqConfig 單元測試")
class ArticleRabbitMqConfigTest {

    /** 本模組根套件，掃描 consumer 用 */
    private static final String BASE_PACKAGE = "dowob.xyz.blog.module.article";

    /** 受測配置類別 */
    private final ArticleRabbitMqConfig config = new ArticleRabbitMqConfig();

    @Test
    @DisplayName("article.published 不再被宣告為 Queue（孤兒 queue 已移除，防止未來誤加回）")
    void articlePublishedQueue_isNoLongerDeclared() {
        Set<String> methodNames = Arrays.stream(ArticleRabbitMqConfig.class.getDeclaredMethods())
                .map(Method::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(methodNames).doesNotContain("articlePublishedQueue", "articlePublishedBinding");
    }

    @Test
    @DisplayName("QUEUE_PUBLISHED 常數已移除（不再被任何人引用）")
    void queuePublishedConstant_isRemoved() {
        Set<String> fieldNames = Arrays.stream(ArticleRabbitMqConfig.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(fieldNames).doesNotContain("QUEUE_PUBLISHED");
    }

    @Test
    @DisplayName("ROUTING_KEY_PUBLISHED 仍存在（producer 發布事件、search/recommend 模組訂閱皆需要）")
    void routingKeyPublished_stillExists() {
        assertThat(ArticleRabbitMqConfig.ROUTING_KEY_PUBLISHED).isEqualTo("article.published");
    }

    @Test
    @DisplayName("本模組宣告的每個 Queue，本模組類別路徑內都有對應的 @RabbitListener consumer")
    void everyDeclaredQueue_hasAConsumerInThisModule() throws Exception {
        Set<String> declaredQueueNames = declaredQueueNames();
        Set<String> consumedQueueNames = scanRabbitListenerQueueNames();

        assertThat(declaredQueueNames)
                .as("本模組 RabbitMqConfig 宣告但本模組類別路徑內找不到 @RabbitListener 的 queue")
                .allMatch(consumedQueueNames::contains);
    }

    /**
     * 透過反射呼叫 {@link ArticleRabbitMqConfig} 上所有回傳 {@link Queue} 的無參數方法，
     * 取得目前實際宣告的 queue 名稱集合（不手動列舉，避免新增 Queue Bean 時忘記同步更新測試）。
     *
     * @return 目前宣告的 queue 名稱集合
     * @throws Exception 反射呼叫失敗時拋出
     */
    private Set<String> declaredQueueNames() throws Exception {
        Set<String> names = new HashSet<>();
        for (Method method : ArticleRabbitMqConfig.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && Queue.class.equals(method.getReturnType())) {
                method.setAccessible(true);
                Queue queue = (Queue) method.invoke(config);
                names.add(queue.getName());
            }
        }
        return names;
    }

    /**
     * 掃描本模組類別路徑下所有 {@code @Component}，收集其方法上 {@code @RabbitListener}
     * 宣告的 queue 名稱。
     *
     * @return 本模組實際消費的 queue 名稱集合
     * @throws Exception 載入類別失敗時拋出
     */
    private Set<String> scanRabbitListenerQueueNames() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Component.class));

        Set<String> queueNames = new HashSet<>();
        for (var beanDefinition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> clazz = Class.forName(beanDefinition.getBeanClassName());
            for (Method method : clazz.getDeclaredMethods()) {
                RabbitListener listener = method.getAnnotation(RabbitListener.class);
                if (listener != null) {
                    queueNames.addAll(Arrays.asList(listener.queues()));
                }
            }
        }
        return queueNames;
    }
}
