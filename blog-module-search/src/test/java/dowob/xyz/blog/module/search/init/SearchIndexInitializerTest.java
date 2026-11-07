package dowob.xyz.blog.module.search.init;

import dowob.xyz.blog.module.search.index.ArticleIndexAdmin;
import dowob.xyz.blog.module.search.index.IndexState;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link SearchIndexInitializer} 單元測試（D1／PERF-01：讀寫改走 alias）
 *
 * <p>初始化的三種起點：</p>
 * <ul>
 *   <li>全新環境（名稱不存在）：建立 {@code blog_articles_v1} 並建立 alias</li>
 *   <li>既有環境（{@code blog_articles} 是實體索引）：以 {@code _reindex} 複製到新的版本化索引後，
 *       在同一個 {@code _aliases} 請求內刪除舊索引並建立 alias；任何一步失敗都維持原索引不動、記 ERROR 寫明復原方式，
 *       不擋啟動（搜尋與增量索引在實體索引上照常運作，{@code POST /reindex} 會完成遷移）</li>
 *   <li>正常狀態（alias）：mapping 的檢查與 W6 的 putMapping 補欄位作用在 alias 背後的實體索引</li>
 * </ul>
 *
 * <p>沿用自改版前的行為：舊 author mapping 只告警、不刪索引；缺頂層 uuid 時 putMapping 增補；putMapping 失敗記 ERROR
 * 寫明復原方式且不拋例外；mapping 不一致（status 非 keyword 或 tags 非 nested）時換成正確 mapping 的索引。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("SearchIndexInitializer 單元測試")
class SearchIndexInitializerTest {

    private static final String PHYSICAL = "blog_articles_v20261101000000000";
    private static final String NEW_INDEX = "blog_articles_v20261107120000000";

    private ArticleIndexAdmin admin;
    private SearchIndexInitializer initializer;

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

    @BeforeEach
    void setUp() {
        admin = mock(ArticleIndexAdmin.class);
        when(admin.newVersionedIndexName()).thenReturn(NEW_INDEX);
        initializer = new SearchIndexInitializer(admin);
    }

    private List<LogEvent> runCapturing(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(SearchIndexInitializer.class);
        CapturingAppender appender = new CapturingAppender();
        appender.start();
        logger.addAppender(appender);
        Level previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(previous);
            appender.stop();
        }
        return appender.events;
    }

    /** 正確 mapping（status keyword、tags nested、頂層 uuid keyword、author.uuid keyword） */
    private static Map<String, Object> validMapping() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("status", Map.of("type", "keyword"));
        properties.put("tags", Map.of("type", "nested"));
        properties.put("uuid", Map.of("type", "keyword"));
        properties.put("author", Map.of("properties", Map.of("uuid", Map.of("type", "keyword"))));
        return Map.of("properties", properties);
    }

    private static Map<String, Object> mappingWith(String field, Object value) {
        Map<String, Object> properties = new HashMap<>(castProperties(validMapping()));
        if (value == null) {
            properties.remove(field);
        } else {
            properties.put(field, value);
        }
        return Map.of("properties", properties);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castProperties(Map<String, Object> mapping) {
        return (Map<String, Object>) mapping.get("properties");
    }

    private void aliasPointingTo(Map<String, Object> mapping) {
        when(admin.inspect()).thenReturn(IndexState.alias(List.of(PHYSICAL)));
        when(admin.getMapping(PHYSICAL)).thenReturn(mapping);
    }

    @Nested
    @DisplayName("全新環境")
    class FreshEnvironment {

        @Test
        @DisplayName("名稱不存在：建立 blog_articles_v1，再以 alias 指向它")
        void absent_createsInitialIndexThenAlias() {
            when(admin.inspect()).thenReturn(IndexState.absent(), IndexState.alias(List.of(ArticleIndexAdmin.INITIAL_INDEX)));
            when(admin.indexExists(ArticleIndexAdmin.INITIAL_INDEX)).thenReturn(false);
            when(admin.getMapping(ArticleIndexAdmin.INITIAL_INDEX)).thenReturn(validMapping());

            initializer.ensureCorrectMapping();

            var order = inOrder(admin);
            order.verify(admin).createIndex(ArticleIndexAdmin.INITIAL_INDEX);
            order.verify(admin).switchAlias(ArticleIndexAdmin.INITIAL_INDEX, IndexState.absent());
            verify(admin, never()).copyDocuments(anyString(), anyString());
        }

        @Test
        @DisplayName("多台同時啟動：建立 v1 失敗但 v1 已存在（別台建好了）→ 不拋例外，照樣建立 alias")
        void absent_createRaceLost_stillAddsAlias() {
            when(admin.inspect()).thenReturn(IndexState.absent(), IndexState.alias(List.of(ArticleIndexAdmin.INITIAL_INDEX)));
            when(admin.indexExists(ArticleIndexAdmin.INITIAL_INDEX)).thenReturn(false, true);
            doThrow(new IllegalStateException("resource_already_exists_exception"))
                    .when(admin).createIndex(ArticleIndexAdmin.INITIAL_INDEX);
            when(admin.getMapping(ArticleIndexAdmin.INITIAL_INDEX)).thenReturn(validMapping());

            initializer.ensureCorrectMapping();

            verify(admin).switchAlias(ArticleIndexAdmin.INITIAL_INDEX, IndexState.absent());
        }

        @Test
        @DisplayName("v1 已存在（先前留下的）：不重建，直接以 alias 指向它")
        void absent_initialIndexAlreadyExists_onlyAddsAlias() {
            when(admin.inspect()).thenReturn(IndexState.absent(), IndexState.alias(List.of(ArticleIndexAdmin.INITIAL_INDEX)));
            when(admin.indexExists(ArticleIndexAdmin.INITIAL_INDEX)).thenReturn(true);
            when(admin.getMapping(ArticleIndexAdmin.INITIAL_INDEX)).thenReturn(validMapping());

            initializer.ensureCorrectMapping();

            verify(admin, never()).createIndex(anyString());
            verify(admin).switchAlias(ArticleIndexAdmin.INITIAL_INDEX, IndexState.absent());
        }

        @Test
        @DisplayName("建立 v1 失敗且 v1 確實不存在（ES 真的有問題）→ 例外往外拋，啟動失敗")
        void absent_createFailsForReal_propagates() {
            when(admin.inspect()).thenReturn(IndexState.absent());
            when(admin.indexExists(ArticleIndexAdmin.INITIAL_INDEX)).thenReturn(false);
            doThrow(new IllegalStateException("es down")).when(admin).createIndex(ArticleIndexAdmin.INITIAL_INDEX);

            assertThatThrownBy(() -> initializer.ensureCorrectMapping()).hasMessage("es down");
            verify(admin, never()).switchAlias(anyString(), any());
        }
    }

    @Nested
    @DisplayName("既有環境：blog_articles 是實體索引")
    class ConcreteIndexMigration {

        @Test
        @DisplayName("複製文件到新的版本化索引，再以單一 _aliases 請求刪除舊索引並建立 alias")
        void concrete_copiesThenSwitchesAtomically() {
            when(admin.inspect()).thenReturn(IndexState.concrete());

            initializer.ensureCorrectMapping();

            var order = inOrder(admin);
            order.verify(admin).createIndex(NEW_INDEX);
            order.verify(admin).copyDocuments(ArticleIndexAdmin.ALIAS, NEW_INDEX);
            order.verify(admin).switchAlias(NEW_INDEX, IndexState.concrete());
            verify(admin, never()).deleteIndexQuietly(ArticleIndexAdmin.ALIAS);
        }

        @Test
        @DisplayName("複製失敗：刪掉新索引、不切換、原實體索引保留；記 ERROR 寫明執行 reindex 即可完成遷移，不擋啟動")
        void concrete_copyFails_keepsOriginalAndLogsRecovery() {
            when(admin.inspect()).thenReturn(IndexState.concrete());
            when(admin.copyDocuments(ArticleIndexAdmin.ALIAS, NEW_INDEX)).thenThrow(new IllegalStateException("reindex failed"));

            List<LogEvent> events = runCapturing(() -> initializer.ensureCorrectMapping());

            verify(admin).deleteIndexQuietly(NEW_INDEX);
            verify(admin, never()).switchAlias(anyString(), any());
            assertThat(events).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage()).contains("reindex");
            });
        }

        @Test
        @DisplayName("多台同時遷移：切換失敗但 alias 已被別台建好 → 刪掉自己的新索引，記 INFO 不記 ERROR")
        void concrete_switchRaceLost_cleansUpQuietly() {
            when(admin.inspect()).thenReturn(IndexState.concrete(), IndexState.alias(List.of("blog_articles_v20261107115959999")));
            doThrow(new IllegalStateException("index_not_found_exception"))
                    .when(admin).switchAlias(NEW_INDEX, IndexState.concrete());

            List<LogEvent> events = runCapturing(() -> initializer.ensureCorrectMapping());

            verify(admin).deleteIndexQuietly(NEW_INDEX);
            assertThat(events).noneSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
        }
    }

    @Nested
    @DisplayName("正常狀態：alias 指向實體索引")
    class AliasPresent {

        @Test
        @DisplayName("mapping 正確（含頂層 uuid 與 author.uuid keyword）：什麼都不做")
        void validMapping_doesNothing() {
            aliasPointingTo(validMapping());

            initializer.ensureCorrectMapping();

            verify(admin, never()).putMapping(anyString());
            verify(admin, never()).createIndex(anyString());
            verify(admin, never()).switchAlias(anyString(), any());
        }

        @Test
        @DisplayName("缺頂層 uuid keyword：對 alias 背後的實體索引 putMapping 增補，不重建")
        void missingTopLevelUuid_putsMappingOnPhysicalIndex() {
            aliasPointingTo(mappingWith("uuid", null));
            when(admin.putMapping(PHYSICAL)).thenReturn(true);

            initializer.ensureCorrectMapping();

            verify(admin).putMapping(PHYSICAL);
            verify(admin, never()).createIndex(anyString());
        }

        @Test
        @DisplayName("putMapping 失敗（uuid 已被動態建成 text）：記 ERROR 寫明復原方式，不拋例外、不刪索引")
        void putMappingFails_logsActionableError() {
            aliasPointingTo(mappingWith("uuid", Map.of("type", "text")));
            when(admin.putMapping(PHYSICAL)).thenThrow(new RuntimeException("mapper [uuid] cannot be changed from type [text] to [keyword]"));

            List<LogEvent> events = runCapturing(() -> initializer.ensureCorrectMapping());

            verify(admin, never()).deleteIndexQuietly(anyString());
            assertThat(events).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage()).contains("刪除索引").contains("reindex");
            });
        }

        @Test
        @DisplayName("putMapping 未被 ES 接受（回 false）：同樣記 ERROR")
        void putMappingNotAcknowledged_logsError() {
            aliasPointingTo(mappingWith("uuid", null));
            when(admin.putMapping(PHYSICAL)).thenReturn(false);

            List<LogEvent> events = runCapturing(() -> initializer.ensureCorrectMapping());

            assertThat(events).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
        }

        @Test
        @DisplayName("舊 author mapping（無 author.uuid）：只記 warn 提示 reindex，不刪除、不重建")
        void legacyAuthorMapping_onlyWarns() {
            aliasPointingTo(mappingWith("author", Map.of("properties", Map.of(
                    "id", Map.of("type", "keyword"), "username", Map.of("type", "keyword")))));

            List<LogEvent> events = runCapturing(() -> initializer.ensureCorrectMapping());

            verify(admin, never()).createIndex(anyString());
            verify(admin, never()).deleteIndexQuietly(anyString());
            assertThat(events).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.WARN);
                assertThat(e.getMessage().getFormattedMessage()).contains("reindex");
            });
        }

        @Test
        @DisplayName("status 非 keyword：換成正確 mapping 的新索引（複製文件、原子切換 alias、刪舊索引）")
        void wrongStatus_migratesToNewIndex() {
            aliasPointingTo(mappingWith("status", Map.of("type", "text")));

            initializer.ensureCorrectMapping();

            var order = inOrder(admin);
            order.verify(admin).createIndex(NEW_INDEX);
            order.verify(admin).copyDocuments(PHYSICAL, NEW_INDEX);
            order.verify(admin).switchAlias(NEW_INDEX, IndexState.alias(List.of(PHYSICAL)));
            order.verify(admin).deleteIndexQuietly(PHYSICAL);
        }

        @Test
        @DisplayName("tags 非 nested：同樣換成新索引")
        void tagsNotNested_migratesToNewIndex() {
            aliasPointingTo(mappingWith("tags", Map.of("type", "object")));

            initializer.ensureCorrectMapping();

            verify(admin).switchAlias(NEW_INDEX, IndexState.alias(List.of(PHYSICAL)));
        }

        @Test
        @DisplayName("mapping 讀不到 properties：視為不一致，換成新索引")
        void mappingWithoutProperties_migrates() {
            aliasPointingTo(Map.of());

            initializer.ensureCorrectMapping();

            verify(admin).switchAlias(eq(NEW_INDEX), any());
        }

        @Test
        @DisplayName("alias 指向多個索引（異常）：記 ERROR 提示執行 reindex 收斂，不動任何索引")
        void multipleTargets_logsErrorWithoutChanges() {
            when(admin.inspect()).thenReturn(IndexState.alias(List.of(PHYSICAL, NEW_INDEX)));

            List<LogEvent> events = runCapturing(() -> initializer.ensureCorrectMapping());

            verify(admin, never()).createIndex(anyString());
            verify(admin, never()).switchAlias(anyString(), any());
            assertThat(events).anySatisfy(e -> {
                assertThat(e.getLevel()).isEqualTo(Level.ERROR);
                assertThat(e.getMessage().getFormattedMessage()).contains("reindex");
            });
        }
    }
}
