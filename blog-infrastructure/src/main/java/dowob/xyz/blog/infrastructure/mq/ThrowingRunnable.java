package dowob.xyz.blog.infrastructure.mq;

/**
 * 允許拋出受檢例外的 {@link Runnable}。
 *
 * <p>供 {@link ConsumerExecutor} 包裝 consumer 業務邏輯使用：
 * 業務邏輯常會呼叫拋受檢例外的 API（序列化、I/O 等），
 * 若用標準 {@link Runnable} 每個呼叫端都得自行 try/catch，違背抽出共用包裝的初衷。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@FunctionalInterface
public interface ThrowingRunnable {

    /**
     * 執行業務邏輯。
     *
     * @throws Exception 業務處理失敗時拋出；由 {@link ConsumerExecutor} 統一處理
     */
    void run() throws Exception;
}
