package dowob.xyz.blog.infrastructure.mq;

/**
 * consumer 業務 body 拋出受檢例外時，{@link ConsumerExecutor} 往外拋的非受檢包裝。
 *
 * <p>{@code @RabbitListener} 方法不宜宣告 {@code throws Exception}，故受檢例外（{@code IOException}、
 * {@code InterruptedException} 等）包成本例外往外拋，讓容器與重試攔截器處理。原始例外保留為 cause，
 * 重試的例外分類（{@code SimpleRetryPolicy} 的 traverseCauses）與日誌皆可看到它。
 * {@link RuntimeException} 與 {@link Error} 則<b>不</b>包裝，原樣往外拋。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class ConsumerExecutionException extends RuntimeException {

    /**
     * 建立包裝例外。
     *
     * @param consumerName consumer 識別名稱
     * @param cause        業務 body 拋出的原始（受檢）例外
     */
    public ConsumerExecutionException(String consumerName, Exception cause) {
        super("consumer " + consumerName + " 處理失敗：" + cause.getMessage(), cause);
    }
}
