package dowob.xyz.blog.module.search.init;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SearchIndexInitializer} 的啟動時序守衛：索引初始化必須<b>早於</b> RabbitListener 容器開始消費。
 *
 * <p>原本是 {@code ApplicationRunner}，在 context refresh 完成<b>之後</b>才執行；而 Rabbit 容器在 refresh
 * 內（SmartLifecycle，phase 預設 {@link Integer#MAX_VALUE}）就開始消費，於是 listener 可能在索引 mapping
 * 就緒前寫入文件，讓 ES 動態把 {@code uuid} 等欄位建成 text。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SearchIndexInitializer 啟動時序")
class SearchIndexInitializerLifecycleTest {

    private ElasticsearchOperations operationsRecording(List<String> order) {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOps = mock(IndexOperations.class);
        when(operations.indexOps(ArticleDocument.class)).thenReturn(indexOps);
        when(indexOps.exists()).thenAnswer(inv -> {
            order.add("initializer");
            return true;
        });
        when(indexOps.getMapping()).thenReturn(Map.of("properties", Map.of(
                "status", Map.of("type", "keyword"),
                "tags", Map.of("type", "nested"),
                "uuid", Map.of("type", "keyword"),
                "author", Map.of("properties", Map.of("uuid", Map.of("type", "keyword"))))));
        return operations;
    }

    /** 模擬 Rabbit 容器：phase 與 {@link RabbitListenerEndpointRegistry} 相同，start 時記錄「開始消費」。 */
    private static final class ListenerContainerProbe implements SmartLifecycle {
        private final List<String> order;
        private final AtomicBoolean running = new AtomicBoolean();

        private ListenerContainerProbe(List<String> order) {
            this.order = order;
        }

        @Override
        public void start() {
            order.add("listener-start");
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public int getPhase() {
            return new RabbitListenerEndpointRegistry().getPhase();
        }
    }

    @Test
    @DisplayName("initializer 是 SmartLifecycle，且 phase 低於 RabbitListenerEndpointRegistry（先 start）")
    void initializer_isSmartLifecycleWithPhaseBelowRabbitRegistry() {
        Object initializer = new SearchIndexInitializer(mock(ElasticsearchOperations.class));

        assertThat(initializer).isInstanceOf(SmartLifecycle.class);
        assertThat(((SmartLifecycle) initializer).getPhase())
                .isLessThan(new RabbitListenerEndpointRegistry().getPhase());
        assertThat(((SmartLifecycle) initializer).isAutoStartup()).isTrue();
    }

    @Test
    @DisplayName("不再是 ApplicationRunner（否則 refresh 後會再初始化一次）")
    void initializer_isNoLongerApplicationRunner() {
        assertThat((Object) new SearchIndexInitializer(mock(ElasticsearchOperations.class)))
                .isNotInstanceOf(ApplicationRunner.class);
    }

    @Test
    @DisplayName("context 啟動時序：初始化發生在 Rabbit 容器開始消費之前")
    void contextStartup_initializesIndexBeforeListenerContainersStart() {
        List<String> order = new ArrayList<>();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("searchIndexInitializer", SearchIndexInitializer.class,
                    () -> new SearchIndexInitializer(operationsRecording(order)));
            context.registerBean("listenerProbe", ListenerContainerProbe.class, () -> new ListenerContainerProbe(order));
            context.refresh();
        }

        assertThat(order).containsExactly("initializer", "listener-start");
    }

    @Test
    @DisplayName("ES 不可用：啟動照舊失敗（與原 ApplicationRunner 一致），listener 不會開始消費")
    void contextStartup_elasticsearchDown_failsStartupBeforeListenersStart() {
        List<String> order = new ArrayList<>();
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOps = mock(IndexOperations.class);
        when(operations.indexOps(ArticleDocument.class)).thenReturn(indexOps);
        when(indexOps.exists()).thenThrow(new IllegalStateException("es down"));
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("searchIndexInitializer", SearchIndexInitializer.class,
                () -> new SearchIndexInitializer(operations));
        context.registerBean("listenerProbe", ListenerContainerProbe.class, () -> new ListenerContainerProbe(order));

        assertThatThrownBy(context::refresh).hasRootCauseMessage("es down");
        assertThat(order).doesNotContain("listener-start");
        context.close();
    }
}
