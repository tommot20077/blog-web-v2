package dowob.xyz.blog.infrastructure.event;

/**
 * 使用者事件契約：exchange 與 routing key 常數（設計 §3.1「事件契約位置」）。
 *
 * <p>user 是這些事件的 producer，並於自己的 {@code UserRabbitMqConfig} 宣告 exchange；訂閱端
 * （article／reading／version／tag／file／search）各自宣告 queue 並以相同的 routing key 綁定。
 * 常數與事件類別一併放在 blog-infrastructure，讓訂閱端不必 import user 模組。
 * 與 {@link ArticleEventContract}、{@link SeriesEventContract} 刻意分成不同 exchange：
 * 三者的 producer 是不同模組，日後拆服務時各自獨立，不共用 exchange。</p>
 *
 * <p>{@code user.events} 同時承載 user 模組內部的註冊、重設密碼事件（routing key
 * {@code user.registered}、{@code user.password.reset}，僅 user 模組自己消費，不屬於本契約）。</p>
 *
 * <p><b>字串值是線上協定，不得變更</b>：exchange 與 routing key 一旦改動，已宣告的 queue 綁定會失效。
 * {@code UserEventContractTest} 把這些值釘死。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class UserEventContract {

    /** 使用者事件 Topic Exchange 名稱 */
    public static final String EXCHANGE = "user.events";

    /**
     * 使用者帳號已刪除（匿名化）Routing Key。事件類別為 {@link UserDeletedEvent}。
     *
     * <p>訂閱端：article、reading、version、tag、file、search 模組各自的 {@code *.user-deleted} queue。
     * topic exchange 在沒有任何 queue 綁定時會直接丟棄訊息，發送端不受影響。</p>
     */
    public static final String ROUTING_KEY_DELETED = "user.deleted";

    /** 工具類別，禁止實例化。 */
    private UserEventContract() {
    }
}
