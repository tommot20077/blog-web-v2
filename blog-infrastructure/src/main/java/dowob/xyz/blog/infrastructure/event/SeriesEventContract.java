package dowob.xyz.blog.infrastructure.event;

/**
 * 系列事件契約：exchange 與 routing key 常數（設計 §3.1「事件契約位置」）。
 *
 * <p>series 是這些事件的 producer，並於自己的 {@code SeriesRabbitMqConfig} 宣告 exchange；訂閱端
 * （M6-b 起的 article）各自宣告 queue 並以相同的 routing key 綁定。常數與事件類別一併放在 blog-infrastructure，
 * 讓訂閱端不必 import series 模組。與 {@link ArticleEventContract} 刻意分成兩個 exchange：
 * 兩者的 producer 是不同模組，日後拆服務時各自獨立，不共用 exchange。</p>
 *
 * <p><b>字串值是線上協定，不得變更</b>：exchange 與 routing key 一旦改動，已宣告的 queue 綁定會失效。
 * {@code SeriesEventContractTest} 把這些值釘死。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class SeriesEventContract {

    /** 系列事件 Topic Exchange 名稱 */
    public static final String EXCHANGE = "series.events";

    /**
     * 系列已刪除 Routing Key。事件類別為 {@link SeriesDeletedEvent}。
     *
     * <p>訂閱端：article 模組的 {@code article.series-deleted} queue（M6-b）。topic exchange 在沒有任何 queue
     * 綁定時會直接丟棄訊息，發送端不受影響。</p>
     */
    public static final String ROUTING_KEY_DELETED = "series.deleted";

    /** 工具類別，禁止實例化。 */
    private SeriesEventContract() {
    }
}
