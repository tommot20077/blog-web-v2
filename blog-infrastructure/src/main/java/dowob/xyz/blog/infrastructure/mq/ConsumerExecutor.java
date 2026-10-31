package dowob.xyz.blog.infrastructure.mq;

import dowob.xyz.blog.infrastructure.idempotency.IdempotencyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * RabbitMQ consumer 的共用執行包裝：失敗日誌、計數、（選用的）冪等交易。
 *
 * <p><b>契約（Z3-b 起）：失敗往外拋</b>。成功就正常返回、失敗就把例外往外拋，
 * 由 Spring AMQP 容器與重試攔截器處理（ack 模式為 {@code AUTO}，容器於 listener 返回後 ack、拋出例外則 nack）：</p>
 * <ul>
 *   <li>body 成功 → 正常返回（容器 ack）</li>
 *   <li>body 拋 {@link RuntimeException} → log.error、計數後<b>原樣</b>往外拋</li>
 *   <li>body 拋受檢例外 → log.error、計數後包成 {@link ConsumerExecutionException} 往外拋
 *       （{@link InterruptedException} 並還原執行緒的 interrupt 旗標）</li>
 *   <li>容器 nack 後由 {@code StatefulRetryOperationsInterceptor} 依 {@code RabbitMqConfig} 的重試政策重試
 *       （最多 3 次、1s→5s 退避），耗盡由 recoverer 以 requeue=false 拒絕，經 DLX 進 DLQ</li>
 * </ul>
 * <p>本類<b>不</b>自己 ack／nack，也不知道重試與 DLQ——這些由容器處理。因此 consumer 方法不需要
 * {@code Channel}／{@code deliveryTag}。</p>
 *
 * <p><b>冪等版本</b>：{@link #executeIdempotent} 把 {@code processed_events} 的標記與業務
 * 放進<b>同一個資料庫交易</b>，詳見該方法的 JavaDoc。失敗 → 標記 rollback → 重試時業務會真的重做。</p>
 *
 * <p><b>指標</b>（全域 registry 未掛任何子 registry 時整段跳過，與 {@code BatchedQuery} 一致）：</p>
 * <ul>
 *   <li>{@value #PROCESSED_METRIC}（tag {@code consumer}）：body 成功（含冪等跳過重複事件）</li>
 *   <li>{@value #FAILED_METRIC}（tag {@code consumer}）：<b>每次嘗試</b>失敗各計一次
 *       （重試 3 次全失敗 ⇒ 3）</li>
 *   <li>{@value #DEAD_LETTERED_METRIC}（tag {@code queue}）：重試耗盡、不可重試（如訊息轉換失敗）或無 messageId
 *       而被容器以 requeue=false 拒絕、交給 DLX 的訊息。<b>不在本類計數</b>，
 *       而是在容器層的 {@link DeadLetterCountingErrorHandler} 計——只有那裡才知道「這則訊息不會再被重試了」</li>
 * </ul>
 *
 * @author Yuan
 * @version 3.0
 */
@Slf4j
@Component
public class ConsumerExecutor {

    /** 成功處理（正常返回，容器隨後 ack）的訊息數指標名稱 */
    public static final String PROCESSED_METRIC = "mq.consumer.processed";

    /** 處理失敗的訊息數指標名稱 */
    public static final String FAILED_METRIC = "mq.consumer.failed";

    /** 被容器以 requeue=false 拒絕、交由 DLX 的訊息數指標名稱（tag：queue，由 {@link DeadLetterCountingErrorHandler} 計數） */
    public static final String DEAD_LETTERED_METRIC = "mq.consumer.dead_lettered";

    /** 指標的 consumer tag 名稱 */
    private static final String CONSUMER_TAG = "consumer";

    /** 冪等標記與業務共用的交易範本（propagation REQUIRED）；沒有 datasource 的模組為 {@code null} */
    @Nullable
    private final TransactionTemplate transactionTemplate;

    /** 冪等標記服務；沒有 datasource 的模組為 {@code null} */
    @Nullable
    private final IdempotencyService idempotencyService;

    /**
     * Spring 注入用建構子。
     *
     * <p>兩個參數皆允許為 {@code null}：user、recommend 等沒有 datasource 的模組不會建立
     * {@link IdempotencyService}，其 consumer 只使用不帶冪等的 {@code execute} 系列方法。
     * 這類模組若誤呼叫 {@link #executeIdempotent} 帶非 null 的 eventId，會明確失敗（往外拋 {@link IllegalStateException}），
     * 而不是靜默略過去重。</p>
     *
     * @param transactionTemplate 交易範本；可為 {@code null}
     * @param idempotencyService  冪等標記服務；可為 {@code null}
     */
    @Autowired
    public ConsumerExecutor(@Nullable TransactionTemplate transactionTemplate,
                            @Nullable IdempotencyService idempotencyService) {
        this.transactionTemplate = transactionTemplate;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 不具冪等能力的建構子，只能使用 {@code execute} 系列方法（供單元測試與無 datasource 的情境）。
     */
    public ConsumerExecutor() {
        this(null, null);
    }

    /**
     * 以「標記與業務同一個資料庫交易」的方式執行 consumer 業務；失敗往外拋。
     *
     * <p><b>流程</b>（{@code eventId} 非 null 時）：在一個 {@link TransactionTemplate}
     * （propagation REQUIRED）交易內依序：</p>
     * <ol>
     *   <li>{@code INSERT INTO processed_events ... ON CONFLICT (event_id, consumer_name) DO NOTHING}
     *       （{@link IdempotencyService#tryMark}）</li>
     *   <li>影響 0 列＝已處理過 → 不執行 body、記 INFO、commit 空交易後正常返回（容器 ack）</li>
     *   <li>影響 1 列 → 執行 body → commit → 正常返回（容器 ack）</li>
     * </ol>
     * <p>body 拋任何例外 → 交易 rollback（標記<b>一起消失</b>）→ 與 {@link #execute} 相同：
     * log.error、計 failed、往外拋。因此重試（以及 DLQ 人工重投）時，失敗過的訊息不會被誤判為已處理，
     * 業務會真的重做。commit 本身失敗（例如延遲到 commit 才檢查的約束）同樣往外拋，標記與業務一起沒有生效。</p>
     *
     * <p><b>為什麼先 INSERT 再執行 body</b>：同一 eventId 併發重送時，第二個交易的 INSERT 會被第一個交易
     * 持有的 row lock 擋住——第一個 commit 後，第二個看到衝突而跳過；第一個 rollback 後，第二個插入成功並
     * 執行。因此不會兩個都執行，也不會兩個都跳過。若改成「先執行 body 再標記」，兩個交易會同時執行 body，
     * 衝突要到最後才發現。</p>
     *
     * <p><b>業務不寫資料庫時</b>（Redis、Elasticsearch、寄信、S3）：同一模式代表「body 成功後標記才 commit」。
     * body 成功但 commit 失敗 → 標記消失、例外往外拋、重試時會<b>再執行一次 body</b>（at-least-once）；
     * body 在外部系統的效果無法隨交易 rollback，<b>重試會放大這類非交易副作用，最多 MAX_ATTEMPTS（3）倍</b>。
     * 呼叫端須自行評估重複執行的影響。另外，交易會在整個 body 期間佔用一條資料庫連線
     * （例如寄信等待 SMTP 回應期間）；重試的退避睡眠發生在 listener 之外，不佔連線。</p>
     *
     * <p><b>{@code eventId == null}</b>（部署前殘留的舊訊息）：不去重、不開交易，照舊執行 body，
     * 並記 WARN（帶 consumerName 與 logContext）。</p>
     *
     * @param consumerName consumer 識別名稱，作為指標 tag、日誌識別與 {@code processed_events.consumer_name}
     * @param logContext   業務上下文（失敗、跳過、警告日誌用）；可為 {@code null}
     * @param eventId      事件的 dedup key；{@code null} 表示舊訊息，不去重
     * @param body         業務邏輯；拋出任何例外即視為處理失敗並 rollback 標記
     * @throws RuntimeException 處理失敗時：body 的 {@link RuntimeException} 原樣；受檢例外包成
     *                          {@link ConsumerExecutionException}；標記或 commit 失敗為其原始例外
     */
    public void executeIdempotent(String consumerName, Supplier<String> logContext,
                                  UUID eventId, ThrowingRunnable body) {
        if (eventId == null) {
            execute(consumerName, logContext, () -> {
                log.warn("consumer {} 收到沒有 eventId 的舊訊息，略過冪等去重照常處理{}",
                        consumerName, resolveContext(logContext));
                body.run();
            });
            return;
        }
        execute(consumerName, logContext,
                () -> runInIdempotentTransaction(consumerName, logContext, eventId, body));
    }

    /**
     * 在單一資料庫交易內標記並執行 body；body 的例外會原樣（非包裝）往外拋，交易隨之 rollback。
     *
     * @param consumerName consumer 識別名稱
     * @param logContext   業務上下文；可為 {@code null}
     * @param eventId      事件 dedup key（非 null）
     * @param body         業務邏輯
     * @throws Exception body、標記或 commit 失敗時拋出
     */
    private void runInIdempotentTransaction(String consumerName, Supplier<String> logContext,
                                            UUID eventId, ThrowingRunnable body) throws Exception {
        if (transactionTemplate == null || idempotencyService == null) {
            throw new IllegalStateException("consumer " + consumerName
                    + " 使用 executeIdempotent，但目前環境沒有 TransactionTemplate／IdempotencyService（缺少 datasource？）");
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                if (!idempotencyService.tryMark(eventId, consumerName)) {
                    log.info("consumer {} 重複事件已跳過（冪等，不重做業務、正常 ack）- eventId={}{}",
                            consumerName, eventId, resolveContext(logContext));
                    return;
                }
                try {
                    body.run();
                } catch (Exception e) {
                    throw new BodyFailure(e);
                }
            });
        } catch (BodyFailure failure) {
            throw failure.original;
        } catch (TransactionSystemException rollbackFailure) {
            /* body 失敗後 rollback 本身也失敗：Spring 只把原始例外放在 applicationException，不在 cause chain，
             * 日誌會看不到真正的業務失敗原因，故補成 suppressed */
            if (rollbackFailure.getApplicationException() instanceof BodyFailure failure) {
                rollbackFailure.addSuppressed(failure.original);
            }
            throw rollbackFailure;
        }
    }

    /**
     * 讓 checked 例外得以穿過 {@link TransactionTemplate} 的 callback 並觸發 rollback 的內部包裝；
     * 由 {@link #runInIdempotentTransaction} 解開，不會外洩。
     */
    private static final class BodyFailure extends RuntimeException {

        /** 業務 body 拋出的原始例外 */
        private final transient Exception original;

        private BodyFailure(Exception original) {
            super(original.getMessage(), original, false, false);
            this.original = original;
        }
    }

    /**
     * 執行 consumer 業務邏輯（不帶業務上下文）；失敗往外拋。
     *
     * <p>等同 {@link #execute(String, Supplier, ThrowingRunnable)} 的 {@code logContext = null}。</p>
     *
     * @param consumerName consumer 識別名稱，作為指標的 {@code consumer} tag 與日誌識別
     * @param body         業務邏輯；拋出任何例外即視為處理失敗
     * @throws RuntimeException 處理失敗時（見 {@link #execute(String, Supplier, ThrowingRunnable)}）
     */
    public void execute(String consumerName, ThrowingRunnable body) {
        execute(consumerName, null, body);
    }

    /**
     * 執行 consumer 業務邏輯；失敗時記日誌、計數，並把例外<b>往外拋</b>交給容器與重試攔截器。
     *
     * <p>既有 consumer 的錯誤日誌都帶業務識別（articleUuid、eventId、userId……），
     * 供告警與人工補送定位。{@code logContext} 讓呼叫端把這些資訊交給本類統一輸出。
     * 它是 {@link Supplier}，僅在<b>失敗時</b>才求值，成功路徑零成本。</p>
     *
     * <p>失敗時往外拋的例外：{@link RuntimeException} 原樣（同一個實例）；受檢例外包成
     * {@link ConsumerExecutionException}（原始例外為 cause）；{@link InterruptedException} 另會還原
     * 執行緒的 interrupt 旗標。以下情況<b>不會</b>讓業務例外被遮蔽或讓成功被誤判為失敗：</p>
     * <ul>
     *   <li>{@code logContext} 自身拋例外（或本身為 {@code null}）：退回不帶上下文的日誌</li>
     *   <li>計數失敗：只記 warn（見 {@link MqMetrics}）</li>
     * </ul>
     *
     * @param consumerName consumer 識別名稱，作為指標的 {@code consumer} tag 與日誌識別
     * @param logContext   失敗時附加於 ERROR 日誌的業務上下文；可為 {@code null}，
     *                     回傳 {@code null} 或空白亦視為無上下文
     * @param body         業務邏輯；拋出任何例外即視為處理失敗
     * @throws RuntimeException 處理失敗時
     */
    public void execute(String consumerName, Supplier<String> logContext, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception e) {
            throw recordFailure(consumerName, logContext, e);
        }
        increment(PROCESSED_METRIC, consumerName);
    }

    /**
     * 記錄失敗（日誌＋計數）並決定要往外拋的例外。
     *
     * @param consumerName consumer 識別名稱
     * @param logContext   業務上下文；可為 {@code null}
     * @param e            body 拋出的例外
     * @return 要往外拋的非受檢例外：{@code e} 本身（若為 {@link RuntimeException}），否則為其包裝
     */
    private RuntimeException recordFailure(String consumerName, Supplier<String> logContext, Exception e) {
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        log.error("consumer {} 處理訊息失敗（由容器依重試政策重試，耗盡後送往 DLQ）{}",
                consumerName, resolveContext(logContext), e);
        increment(FAILED_METRIC, consumerName);
        return e instanceof RuntimeException runtime ? runtime : new ConsumerExecutionException(consumerName, e);
    }

    /**
     * 求值業務上下文並格式化為日誌後綴。
     *
     * <p>{@link Supplier} 拋任何例外都被吞掉並退回空字串——取日誌上下文不得讓失敗處理本身失敗。</p>
     *
     * @param logContext 上下文來源；可為 {@code null}
     * @return 形如 {@code "，articleUuid=..."} 的後綴；無上下文時為空字串
     */
    private String resolveContext(Supplier<String> logContext) {
        if (logContext == null) {
            return "";
        }
        try {
            String value = logContext.get();
            return value == null || value.isBlank() ? "" : "，" + value;
        } catch (RuntimeException e) {
            log.warn("取得 consumer 日誌上下文失敗，改以不帶上下文輸出", e);
            return "";
        }
    }

    /**
     * 將指定指標加一（tag：consumer）；<b>絕不外拋</b>，見 {@link MqMetrics#increment}。
     *
     * @param metric       指標名稱
     * @param consumerName consumer 識別名稱
     */
    private void increment(String metric, String consumerName) {
        MqMetrics.increment(metric, CONSUMER_TAG, consumerName);
    }
}
