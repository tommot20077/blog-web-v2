package dowob.xyz.blog.common.exception;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler 資料衝突兜底（ARCH-11）測試。
 *
 * <p>以 standalone {@link MockMvc} 掛上真實的 {@link GlobalExceptionHandler}，由測試用 controller
 * 拋出各種例外，驗證的是 Spring 實際的 {@code @ExceptionHandler} 解析結果（含「更具體的 handler 優先」
 * 與「被包在其他例外內」兩種情境），而非直接呼叫 handler 方法。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("GlobalExceptionHandler 資料衝突兜底（ARCH-11）")
class GlobalExceptionHandlerConflictTest {

    /** 模擬 PostgreSQL 的唯一約束違反訊息：含約束名、SQL 與欄位值（回應不得出現其中任何一項） */
    private static final String LEAKY_UNIQUE_MESSAGE = "PreparedStatementCallback; SQL [INSERT INTO user_bookmarks "
            + "(user_uuid, article_uuid) VALUES (?, ?)]; ERROR: duplicate key value violates unique constraint "
            + "\"uq_user_bookmarks_user_article\"\n  Detail: Key (user_uuid, article_uuid)="
            + "(11111111-2222-3333-4444-555555555555, 66666666-7777-8888-9999-000000000000) already exists.";

    /** 約束名稱 */
    private static final String CONSTRAINT_NAME = "uq_user_bookmarks_user_article";

    /** 模擬 NOT NULL 違反訊息 */
    private static final String LEAKY_NOT_NULL_MESSAGE = "ERROR: null value in column \"title\" of relation "
            + "\"articles\" violates not-null constraint\n  Detail: Failing row contains (secret-row-value).";

    private MockMvc mockMvc;

    private LogCaptor logCaptor;

    /**
     * 掛上真實 advice 與丟例外的測試 controller，並開始擷取 GlobalExceptionHandler 的 log。
     */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        logCaptor = LogCaptor.attach(GlobalExceptionHandler.class);
    }

    /**
     * 移除 log 擷取器。
     */
    @AfterEach
    void tearDown() {
        logCaptor.detach();
    }

    /**
     * 驗證：DuplicateKeyException 回 409 與 DATA_CONFLICT，且 body 不洩漏 SQL、約束名、欄位值。
     */
    @Test
    @DisplayName("DuplicateKeyException 應回 409 + DATA_CONFLICT，body 不含 SQL／約束名／欄位值")
    void duplicateKeyException_returns409_withoutLeakingDetails() throws Exception {
        String body = mockMvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.DATA_CONFLICT.getCode()))
                .andExpect(jsonPath("$.message").value(CommonErrorCode.DATA_CONFLICT.getMessage()))
                .andReturn().getResponse().getContentAsString();

        assertNoLeak(body);
    }

    /**
     * 驗證：非唯一鍵的資料完整性違反（NOT NULL）是程式缺陷，重試無效，回 500 而非 409，
     * 以 ERROR 記錄完整例外供監控，且 body 不洩漏 SQL／欄位值。
     */
    @Test
    @DisplayName("DataIntegrityViolationException（NOT NULL）應回 500 + ERROR log，body 不含 SQL／欄位值")
    void dataIntegrityViolation_notNull_returns500_logsError() throws Exception {
        String body = mockMvc.perform(get("/not-null"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("系統內部錯誤"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("title", "articles", "secret-row-value", "not-null");
        assertThat(logCaptor.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrown()).isNotNull();
            assertThat(event.getThrown().getMessage()).contains("secret-row-value");
        });
    }

    /**
     * 驗證：外鍵違反同樣是 500 + ERROR（重試無效）。
     */
    @Test
    @DisplayName("DataIntegrityViolationException（FK）應回 500 + ERROR log，body 不含約束名")
    void dataIntegrityViolation_foreignKey_returns500_logsError() throws Exception {
        String body = mockMvc.perform(get("/foreign-key"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("500"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("fk_comments_parent", "comments");
        assertThat(logCaptor.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrown()).isNotNull();
        });
    }

    /**
     * 驗證：被包在一般 RuntimeException 內的非唯一鍵完整性違反，行為與直接拋出一致（500 + ERROR）。
     */
    @Test
    @DisplayName("被包裝的 NOT NULL 違反與直接拋出一致：500 + ERROR log")
    void wrappedNotNull_returns500_logsError() throws Exception {
        String body = mockMvc.perform(get("/wrapped-not-null"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("500"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("title", "articles", "secret-row-value");
        assertThat(logCaptor.events()).anySatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.ERROR));
        assertThat(logCaptor.events()).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    /**
     * 驗證：OptimisticLockingFailureException 回 409 與 OPTIMISTIC_LOCK_CONFLICT。
     */
    @Test
    @DisplayName("OptimisticLockingFailureException 應回 409 + OPTIMISTIC_LOCK_CONFLICT")
    void optimisticLockingFailure_returns409() throws Exception {
        String body = mockMvc.perform(get("/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT.getCode()))
                .andExpect(jsonPath("$.message").value(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT.getMessage()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("articles", "version", "Row was updated or deleted");
    }

    /**
     * 驗證：BusinessException 仍走原 handler，回原錯誤碼與 400，不被兜底搶走。
     */
    @Test
    @DisplayName("BusinessException 仍走原 handler：400 + 原錯誤碼")
    void businessException_stillHandledByOriginalHandler() throws Exception {
        mockMvc.perform(get("/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.REQUEST_PARAM_MISSING.getCode()))
                .andExpect(jsonPath("$.message").value(CommonErrorCode.REQUEST_PARAM_MISSING.getMessage()));
    }

    /**
     * 驗證：帶自訂狀態的 HttpStatusBusinessException 仍保留其狀態與錯誤碼。
     */
    @Test
    @DisplayName("HttpStatusBusinessException 仍走原 handler：保留自訂狀態與錯誤碼")
    void httpStatusBusinessException_keepsItsOwnStatus() throws Exception {
        mockMvc.perform(get("/business-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.REQUEST_PATH_NOT_FOUND.getCode()));
    }

    /**
     * 驗證：SystemException 仍回 500 與其自身錯誤碼。
     */
    @Test
    @DisplayName("SystemException 仍走原 handler：500 + 原錯誤碼")
    void systemException_stillReturns500() throws Exception {
        mockMvc.perform(get("/system"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.DATABASE_ERROR.getCode()));
    }

    /**
     * 驗證：Spring Data JDBC 的 save 會把約束違反包在一般 RuntimeException（DbActionExecutionException）內，
     * cause 鏈上的 DuplicateKeyException 仍須被辨識為 409。
     */
    @Test
    @DisplayName("被包在一般 RuntimeException 內的 DuplicateKeyException 應回 409（Spring Data JDBC 形式）")
    void wrappedDuplicateKey_returns409() throws Exception {
        String body = mockMvc.perform(get("/wrapped-duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.DATA_CONFLICT.getCode()))
                .andReturn().getResponse().getContentAsString();

        assertNoLeak(body);
    }

    /**
     * 驗證：cause 鏈路徑的 WARN log 帶真實請求 URI（而非占位字串），且記的是外層包裝例外（內層作為 cause 帶出）。
     */
    @Test
    @DisplayName("包裝路徑的 WARN log 含請求 URI，且記外層例外（內層為 cause）")
    void wrappedDuplicateKey_logsRequestUriAndOuterException() throws Exception {
        mockMvc.perform(get("/wrapped-duplicate")).andExpect(status().isConflict());

        assertThat(logCaptor.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage().getFormattedMessage()).contains("/wrapped-duplicate");
            assertThat(event.getMessage().getFormattedMessage()).doesNotContain("(wrapped exception)");
            assertThat(event.getThrown()).isInstanceOf(IllegalStateException.class);
            assertThat(event.getThrown().getCause()).isInstanceOf(DuplicateKeyException.class);
        });
    }

    /**
     * 驗證：包裝的樂觀鎖失敗，WARN log 同樣帶真實請求 URI。
     */
    @Test
    @DisplayName("包裝的樂觀鎖失敗，WARN log 含請求 URI")
    void wrappedOptimisticLock_logsRequestUri() throws Exception {
        mockMvc.perform(get("/wrapped-optimistic-lock")).andExpect(status().isConflict());

        assertThat(logCaptor.events()).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getMessage().getFormattedMessage()).contains("/wrapped-optimistic-lock");
        });
    }

    /**
     * 驗證：被包在一般 RuntimeException 內的 OptimisticLockingFailureException 回 409。
     */
    @Test
    @DisplayName("被包在一般 RuntimeException 內的 OptimisticLockingFailureException 應回 409")
    void wrappedOptimisticLock_returns409() throws Exception {
        mockMvc.perform(get("/wrapped-optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.OPTIMISTIC_LOCK_CONFLICT.getCode()));
    }

    /**
     * 驗證：cause 鏈上沒有資料衝突的未預期例外仍回 500，兜底沒有吞掉真正的系統錯誤。
     */
    @Test
    @DisplayName("無關的未預期例外（含 cause）仍回 500")
    void unrelatedWrappedException_stillReturns500() throws Exception {
        mockMvc.perform(get("/wrapped-unrelated"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("500"))
                .andExpect(jsonPath("$.message").value("系統內部錯誤"));
    }

    /**
     * 驗證：完整例外（含約束名）以 WARN 記錄，方便排查；而回應 body 不含約束名。
     */
    @Test
    @DisplayName("完整例外含約束名以 WARN 記錄；回應 body 不含約束名")
    void duplicateKey_logsFullExceptionAtWarn() throws Exception {
        String body = mockMvc.perform(get("/duplicate"))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(CONSTRAINT_NAME);
        assertThat(logCaptor.events())
                .anySatisfy(event -> {
                    assertThat(event.getLevel()).isEqualTo(Level.WARN);
                    assertThat(event.getMessage().getFormattedMessage()).contains(CONSTRAINT_NAME);
                });
        assertThat(logCaptor.events()).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    /**
     * 斷言回應 body 沒有洩漏 SQL、約束名、欄位值。
     */
    private static void assertNoLeak(String body) {
        assertThat(body).doesNotContain(
                CONSTRAINT_NAME,
                "INSERT INTO",
                "user_bookmarks",
                "duplicate key",
                "11111111-2222-3333-4444-555555555555",
                "Detail");
    }

    /**
     * 丟出各種例外的測試用 controller。
     */
    @RestController
    static class ThrowingController {

        @GetMapping("/duplicate")
        String duplicate() {
            throw new DuplicateKeyException(LEAKY_UNIQUE_MESSAGE);
        }

        @GetMapping("/not-null")
        String notNull() {
            throw new DataIntegrityViolationException(LEAKY_NOT_NULL_MESSAGE);
        }

        @GetMapping("/foreign-key")
        String foreignKey() {
            throw new DataIntegrityViolationException("ERROR: insert or update on table \"comments\" violates "
                    + "foreign key constraint \"fk_comments_parent\"");
        }

        @GetMapping("/optimistic-lock")
        String optimisticLock() {
            throw new OptimisticLockingFailureException(
                    "Row was updated or deleted by another transaction: articles version");
        }

        @GetMapping("/business")
        String business() {
            throw new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);
        }

        @GetMapping("/business-404")
        String businessNotFound() {
            throw new HttpStatusBusinessException(CommonErrorCode.REQUEST_PATH_NOT_FOUND, HttpStatus.NOT_FOUND);
        }

        @GetMapping("/system")
        String system() {
            throw new SystemException(CommonErrorCode.DATABASE_ERROR);
        }

        @GetMapping("/wrapped-duplicate")
        String wrappedDuplicate() {
            throw new IllegalStateException("Failed to execute DbAction.InsertRoot",
                    new DuplicateKeyException(LEAKY_UNIQUE_MESSAGE));
        }

        @GetMapping("/wrapped-optimistic-lock")
        String wrappedOptimisticLock() {
            throw new IllegalStateException("Failed to execute DbAction.UpdateRoot",
                    new OptimisticLockingFailureException("version mismatch"));
        }

        @GetMapping("/wrapped-not-null")
        String wrappedNotNull() {
            throw new IllegalStateException("Failed to execute DbAction.InsertRoot",
                    new DataIntegrityViolationException(LEAKY_NOT_NULL_MESSAGE));
        }

        @GetMapping("/wrapped-unrelated")
        String wrappedUnrelated() {
            throw new IllegalStateException("outer", new IllegalArgumentException("inner"));
        }
    }

    /**
     * 擷取指定類別 logger 輸出的 log4j2 appender。
     */
    private static final class LogCaptor extends AbstractAppender {

        private final List<LogEvent> captured = new CopyOnWriteArrayList<>();
        private final org.apache.logging.log4j.core.Logger logger;
        private final Level originalLevel;

        private LogCaptor(org.apache.logging.log4j.core.Logger logger) {
            super("conflict-test-captor-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY);
            this.logger = logger;
            this.originalLevel = logger.getLevel();
        }

        static LogCaptor attach(Class<?> target) {
            LoggerContext context = (LoggerContext) org.apache.logging.log4j.LogManager.getContext(false);
            org.apache.logging.log4j.core.Logger logger = context.getLogger(target.getName());
            LogCaptor captor = new LogCaptor(logger);
            captor.start();
            logger.addAppender(captor);
            // 測試環境沒有 log4j2 設定檔時預設只輸出 ERROR，須明確打開 WARN 才擷取得到
            logger.setLevel(Level.WARN);
            return captor;
        }

        void detach() {
            logger.removeAppender(this);
            logger.setLevel(originalLevel);
            stop();
        }

        List<LogEvent> events() {
            return new ArrayList<>(captured);
        }

        @Override
        public void append(LogEvent event) {
            captured.add(event.toImmutable());
        }
    }
}
