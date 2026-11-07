package dowob.xyz.blog.mq;

import java.util.List;

/**
 * {@link MqTopologySnapshotTest} 的期望值：由 ARCH-19／ARCH-06 重構<b>之前</b>的程式碼錄製（含 DATA-07 新增的 file.article-deleted）。
 *
 * <p><b>與原錄製的刻意差異（W7-d1，DATA-10／決策 B6）</b>：移除 {@code series.article-deleted} queue 與其對
 * {@code article.events} 的 {@code article.deleted} binding——series 篇數改為即時計數後，該 consumer 成為空殼而被移除。
 * 其餘條目維持原錄製值。</p>
 *
 * @author Yuan
 * @version 1.0
 */
final class MqTopologySnapshotData {

    private MqTopologySnapshotData() {
    }

    /** 全站 queue 快照（name｜屬性｜arguments） */
    static final List<String> QUEUES = List.of(
            "Q article.series-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q article.user-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q article.view.count | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q comment.article-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q file.article-binding | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q file.article-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q file.thumbnail | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q file.user-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q queue.dead-letter | durable=true exclusive=false autoDelete=false | args={}",
            "Q queue.search.index | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q queue.search.index.archive | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q queue.search.index.delete | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q queue.search.index.update | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q reading.article-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q reading.user-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q recommend.article.published | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q search.user-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q series.user-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q tag.article-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q tag.article.tagged | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q tag.usage.article-archived | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q tag.usage.article-published | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q tag.usage.article-updated | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q tag.user-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q user.email.verification | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q user.password.reset | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q user.registration.duplicate-email | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter}",
            "Q version.article-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter, x-message-ttl=600000}",
            "Q version.snapshot | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter, x-message-ttl=600000}",
            "Q version.user-deleted | durable=true exclusive=false autoDelete=false | args={x-dead-letter-exchange=blog.dlq, x-dead-letter-routing-key=dead-letter, x-message-ttl=600000}");

    /** 全站 exchange 快照（以名稱去重） */
    static final List<String> EXCHANGES = List.of(
            "X article.events | type=topic durable=true autoDelete=false internal=false delayed=false | args={}",
            "X blog.dlq | type=direct durable=true autoDelete=false internal=false delayed=false | args={}",
            "X file.topic | type=topic durable=true autoDelete=false internal=false delayed=false | args={}",
            "X series.events | type=topic durable=true autoDelete=false internal=false delayed=false | args={}",
            "X user.events | type=topic durable=true autoDelete=false internal=false delayed=false | args={}");

    /** 全站 binding 快照 */
    static final List<String> BINDINGS = List.of(
            "B article.events -> QUEUE:article.view.count [article.viewed] args={}",
            "B article.events -> QUEUE:comment.article-deleted [article.deleted] args={}",
            "B article.events -> QUEUE:file.article-binding [article.content.changed] args={}",
            "B article.events -> QUEUE:file.article-deleted [article.deleted] args={}",
            "B article.events -> QUEUE:queue.search.index [article.published] args={}",
            "B article.events -> QUEUE:queue.search.index.archive [article.archived] args={}",
            "B article.events -> QUEUE:queue.search.index.delete [article.deleted] args={}",
            "B article.events -> QUEUE:queue.search.index.update [article.updated] args={}",
            "B article.events -> QUEUE:reading.article-deleted [article.deleted] args={}",
            "B article.events -> QUEUE:recommend.article.published [article.published] args={}",
            "B article.events -> QUEUE:tag.article-deleted [article.deleted] args={}",
            "B article.events -> QUEUE:tag.article.tagged [article.tagged] args={}",
            "B article.events -> QUEUE:tag.usage.article-archived [article.archived] args={}",
            "B article.events -> QUEUE:tag.usage.article-published [article.published] args={}",
            "B article.events -> QUEUE:tag.usage.article-updated [article.updated] args={}",
            "B article.events -> QUEUE:version.article-deleted [article.deleted] args={}",
            "B article.events -> QUEUE:version.snapshot [article.content.changed] args={}",
            "B blog.dlq -> QUEUE:queue.dead-letter [dead-letter] args={}",
            "B file.topic -> QUEUE:file.thumbnail [file.image.uploaded] args={}",
            "B series.events -> QUEUE:article.series-deleted [series.deleted] args={}",
            "B user.events -> QUEUE:article.user-deleted [user.deleted] args={}",
            "B user.events -> QUEUE:file.user-deleted [user.deleted] args={}",
            "B user.events -> QUEUE:reading.user-deleted [user.deleted] args={}",
            "B user.events -> QUEUE:search.user-deleted [user.deleted] args={}",
            "B user.events -> QUEUE:series.user-deleted [user.deleted] args={}",
            "B user.events -> QUEUE:tag.user-deleted [user.deleted] args={}",
            "B user.events -> QUEUE:user.email.verification [user.registered] args={}",
            "B user.events -> QUEUE:user.password.reset [user.password.reset] args={}",
            "B user.events -> QUEUE:user.registration.duplicate-email [user.registration.duplicate-email] args={}",
            "B user.events -> QUEUE:version.user-deleted [user.deleted] args={}");
}
