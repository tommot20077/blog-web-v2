package dowob.xyz.blog.infrastructure.event;

/**
 * 文章事件契約：exchange 與 routing key 常數（設計 §3.1「事件契約位置」）。
 *
 * <p>article 是這些事件的 producer，series／version／tag／search／recommend 各自宣告 queue 並以
 * 相同的 routing key 綁定。常數與事件類別一併放在 blog-infrastructure，
 * 讓訂閱端不必 import article 模組（拆服務時 article 不需要被其他服務依賴）。</p>
 *
 * <p><b>字串值是線上協定，不得變更</b>：exchange 與 routing key 一旦改動，已宣告的 queue 綁定會失效。
 * {@code ArticleEventContractTest} 把這些值釘死。</p>
 *
 * <p>僅 article 模組內部使用的常數（瀏覽計數 queue、{@code article.viewed} routing key）
 * 不在此契約內，仍留在 article 模組的 {@code ArticleRabbitMqConfig}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class ArticleEventContract {

    /** 文章事件 Topic Exchange 名稱 */
    public static final String EXCHANGE = "article.events";

    /**
     * 文章已發布 Routing Key。
     *
     * <p>article 為 producer；search／recommend 模組各自宣告 queue 綁定同一 routing key
     * （topic exchange 會把訊息複製給每個綁定的 queue）。事件類別為 {@link ArticlePublishedEvent}。</p>
     */
    public static final String ROUTING_KEY_PUBLISHED = "article.published";

    /** 文章已更新 Routing Key（search 訂閱）。事件類別為 {@link ArticleUpdatedEvent}。 */
    public static final String ROUTING_KEY_UPDATED = "article.updated";

    /**
     * 文章已刪除 Routing Key（series、search 訂閱）。事件類別為 {@link ArticleDeletedEvent}。
     *
     * <p>與 {@link #ROUTING_KEY_ARCHIVED} 刻意分開：series 訂閱本 key 並遞減 {@code series.article_count}，
     * 下架若共用本 key 會讓該計數多扣一次。</p>
     */
    public static final String ROUTING_KEY_DELETED = "article.deleted";

    /** 文章被標記標籤 Routing Key（tag 訂閱）。事件類別為 {@link ArticleTagEvent}。 */
    public static final String ROUTING_KEY_TAGGED = "article.tagged";

    /**
     * 文章內容變更 Routing Key（SAVED / PUBLISHED / RESTORED，version 訂閱）。
     * 事件類別為 {@link ArticleContentChangedEvent}。
     */
    public static final String ROUTING_KEY_CONTENT_CHANGED = "article.content.changed";

    /**
     * 文章已下架（PUBLISHED → ARCHIVED）Routing Key（search 訂閱）。
     * 事件類別為 {@link ArticleArchivedEvent}。
     */
    public static final String ROUTING_KEY_ARCHIVED = "article.archived";

    /** 工具類別，禁止實例化。 */
    private ArticleEventContract() {
    }
}
