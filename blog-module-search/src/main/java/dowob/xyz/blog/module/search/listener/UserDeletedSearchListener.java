package dowob.xyz.blog.module.search.listener;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.UserDisplayInfo;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.search.config.SearchRabbitMqConfig;
import dowob.xyz.blog.module.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Search 模組訂閱 {@link UserDeletedEvent}：使用者刪除帳號（匿名化，DATA-02）後，
 * 把已發布文章在 ES 索引內冗餘的作者暱稱改為匿名暱稱，並清除該使用者的搜尋歷史。
 *
 * <p><b>查證結果</b>：ES 文件的 {@code author} 存了 {@code uuid} 與 {@code nickname}（見 {@code ArticleDocument.AuthorInfo}），
 * 搜尋結果的 {@code authorNickname} 直接取自 ES 而不經 {@code UserFacade}。因此 users 列匿名化後，
 * 若不處理，搜尋仍會顯示原暱稱——需要改寫該作者所有文件的 {@code author.nickname}。
 * 作法是 {@code update_by_query}（以 {@code author.uuid} 為條件），而非逐篇 reindex：只動一個欄位、不需要取回文章內容。</p>
 *
 * <p>匿名暱稱的來源是 {@link UserFacade}（users 列已 commit 匿名化；事件本身不帶任何個資）。
 * 查不到時退回固定的「已刪除使用者」，仍須蓋掉索引內的原暱稱。搜尋歷史存在 Redis（{@code search:history:{userUuid}}），一併清除。</p>
 *
 * <p>冪等性：改寫是「設定為某值」，重送得到相同結果；清除歷史亦同，因此不使用 IdempotencyService。
 * 之後全量重建索引（{@code reindexAll}）讀到的也是匿名化後的暱稱，不會把原暱稱寫回。
 * ACK 策略：AUTO；失敗往外拋，由容器重試，耗盡進 DLQ。缺 {@code userUuid} 的 payload 記 warn 後 ack。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserDeletedSearchListener {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "search.user-deleted";

    /** UserFacade 查不到使用者時使用的匿名暱稱 */
    static final String FALLBACK_NICKNAME = dowob.xyz.blog.module.search.document.ArticleDocument.ANONYMOUS_NICKNAME;

    /** 搜尋服務 */
    private final SearchService searchService;

    /** 使用者 Facade（取得匿名化後的暱稱） */
    private final UserFacade userFacade;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理使用者帳號刪除事件。
     *
     * @param event 使用者帳號刪除事件
     */
    @RabbitListener(queues = SearchRabbitMqConfig.QUEUE_SEARCH_USER_DELETED)
    public void onUserDeleted(UserDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                () -> {
                    UUID userUuid = event.userUuid();
                    if (userUuid == null) {
                        log.warn("使用者刪除事件缺少 userUuid，略過搜尋索引作者暱稱改寫 eventId={}", event.eventId());
                        return;
                    }
                    UserDisplayInfo info = userFacade.getDisplayInfoByUuids(List.of(userUuid)).get(userUuid);
                    String nickname = info != null && info.nickname() != null ? info.nickname() : FALLBACK_NICKNAME;
                    long updated = searchService.updateAuthorNickname(userUuid, nickname);
                    searchService.clearHistory(userUuid);
                    log.info("使用者刪除連動：已改寫索引內作者暱稱並清除搜尋歷史 userUuid={} eventId={} documents={}",
                            userUuid, event.eventId(), updated);
                });
    }
}
