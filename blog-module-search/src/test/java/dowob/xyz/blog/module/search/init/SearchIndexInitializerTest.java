package dowob.xyz.blog.module.search.init;

import dowob.xyz.blog.module.search.document.ArticleDocument;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SearchIndexInitializer} 單元測試：舊 author mapping 只告警、不刪索引
 *
 * <p>沒有任何查詢、排序或 aggregation 用到 {@code author.*}，因此舊 author mapping 不影響搜尋；
 * 刪索引會讓搜尋在 admin reindex 前整個回空，且多台機器同時啟動時會互刪。
 * 遷移改由 {@code reindexAll} 以 id 覆寫文件完成。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("SearchIndexInitializer 單元測試")
class SearchIndexInitializerTest {

    /** 捕捉日誌事件的測試用 appender */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("initializer-test-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }

    private Map<String, Object> mapping(Map<String, Object> author) {
        Map<String, Object> properties = new java.util.HashMap<>();
        properties.put("status", Map.of("type", "keyword"));
        properties.put("tags", Map.of("type", "nested"));
        if (author != null) {
            properties.put("author", Map.of("properties", author));
        }
        return Map.of("properties", properties);
    }

    @Test
    @DisplayName("舊 author mapping（無 author.uuid）：不刪除、不重建，只記 warn 提示執行 admin reindex")
    void ensureCorrectMapping_legacyAuthorMapping_shouldOnlyWarn() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOps = mock(IndexOperations.class);
        when(operations.indexOps(ArticleDocument.class)).thenReturn(indexOps);
        when(indexOps.exists()).thenReturn(true);
        when(indexOps.getMapping()).thenReturn(mapping(Map.of(
                "id", Map.of("type", "keyword"),
                "username", Map.of("type", "keyword"),
                "nickname", Map.of("type", "text"))));
        Logger logger = (Logger) LogManager.getLogger(SearchIndexInitializer.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            new SearchIndexInitializer(operations).ensureCorrectMapping();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }

        verify(indexOps, never()).delete();
        verify(indexOps, never()).createWithMapping();
        assertThat(appender.events).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getMessage().getFormattedMessage()).contains("reindex");
        });
    }

    @Test
    @DisplayName("status 非 keyword：仍砍掉重建（既有行為保持）")
    void ensureCorrectMapping_wrongStatus_shouldStillRecreate() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOps = mock(IndexOperations.class);
        when(operations.indexOps(ArticleDocument.class)).thenReturn(indexOps);
        when(indexOps.exists()).thenReturn(true);
        when(indexOps.getMapping()).thenReturn(Map.of("properties", Map.of("status", Map.of("type", "text"))));

        new SearchIndexInitializer(operations).ensureCorrectMapping();

        verify(indexOps).delete();
        verify(indexOps).createWithMapping();
    }

    @Test
    @DisplayName("mapping 正確（含 author.uuid keyword）：不刪不重建")
    void ensureCorrectMapping_validMapping_shouldDoNothing() {
        ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
        IndexOperations indexOps = mock(IndexOperations.class);
        when(operations.indexOps(ArticleDocument.class)).thenReturn(indexOps);
        when(indexOps.exists()).thenReturn(true);
        when(indexOps.getMapping()).thenReturn(mapping(Map.of(
                "uuid", Map.of("type", "keyword"), "nickname", Map.of("type", "text"))));

        new SearchIndexInitializer(operations).ensureCorrectMapping();

        verify(indexOps, never()).delete();
        verify(indexOps, never()).createWithMapping();
    }
}
