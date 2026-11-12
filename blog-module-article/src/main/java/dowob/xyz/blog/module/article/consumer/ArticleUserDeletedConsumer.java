package dowob.xyz.blog.module.article.consumer;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.ArticleErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig;
import dowob.xyz.blog.module.article.mapper.ArticleAuthorCleanupMapper;
import dowob.xyz.blog.module.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Article 模組訂閱 {@link UserDeletedEvent}：作者刪除帳號（匿名化，DATA-02）後，移除其草稿、待審與被駁回的文章。
 *
 * <p><b>去留政策</b>（Q2 定案：保留已發布、刪除草稿／待審）由 {@link ArticleStatus#isRemovedWithAuthorAccount()}
 * 唯一決定：</p>
 * <ul>
 *   <li>DRAFT、PENDING_REVIEW、REJECTED → 刪除。REJECTED 一併刪除的判斷：被駁回的文章從未公開，只有作者與管理員看得到，
 *       作者已不在，保留只是在資料庫留下一份無人能編輯、也不會被公開的個人內容；</li>
 *   <li>PUBLISHED → 保留，作者顯示因 users 列匿名化而自動變成匿名暱稱；</li>
 *   <li>ARCHIVED → 保留。下架用於法務／侵權撤下，內容可能需要留存，不由使用者刪帳連動銷毀；它不公開，保留不構成對外暴露。</li>
 * </ul>
 *
 * <p><b>走既有刪除流程</b>：逐篇呼叫 {@link ArticleService#deleteArticle}（以作者本人身分），讓它發出的
 * {@code ArticleDeletedEvent} 自然連動 comment／reading／tag／version／series／search 的既有清理，不另寫刪除 SQL。</p>
 *
 * <p>冪等性：查詢只會找到「尚未刪除」的文章；重送時已刪除的不再出現，併發刪除造成的 {@code ARTICLE_NOT_FOUND}
 * 視為已完成。某篇失敗時<b>其餘文章仍會嘗試</b>，最後把例外往外拋，由容器重試（重試只會處理剩下的），
 * 耗盡進 DLQ。因此不使用 IdempotencyService。</p>
 *
 * <p>ACK 策略：AUTO；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理。缺 {@code userUuid} 的 payload
 * 記 warn 後 ack（重送也不會變好）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArticleUserDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "article.user-deleted";

    /** 作者刪帳時應移除的文章狀態名稱（依 {@link ArticleStatus#isRemovedWithAuthorAccount()} 推得） */
    private static final List<String> REMOVABLE_STATUSES = Arrays.stream(ArticleStatus.values())
            .filter(ArticleStatus::isRemovedWithAuthorAccount)
            .map(Enum::name)
            .toList();

    /** 作者名下文章查詢用的 mapper */
    private final ArticleAuthorCleanupMapper cleanupMapper;

    /** 既有的文章刪除流程（含 ArticleDeletedEvent 發送） */
    private final ArticleService articleService;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理使用者帳號刪除事件。
     *
     * @param event 使用者帳號刪除事件
     */
    @RabbitListener(queues = ArticleRabbitMqConfig.QUEUE_USER_DELETED)
    public void onUserDeleted(UserDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.userUuid() == null) {
                        log.warn("使用者刪除事件缺少 userUuid，略過作者文章連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    List<UUID> targets = cleanupMapper.findUuidsByAuthorAndStatuses(event.userUuid(), REMOVABLE_STATUSES)
                            .stream().map(UUID::fromString).toList();
                    int deleted = 0;
                    List<RuntimeException> failures = new ArrayList<>();
                    for (UUID articleUuid : targets) {
                        try {
                            articleService.deleteArticle(event.userUuid(), Role.USER, articleUuid);
                            deleted++;
                        } catch (BusinessException e) {
                            if (!ArticleErrorCode.ARTICLE_NOT_FOUND.getCode().equals(e.getCode())) {
                                failures.add(e);
                            }
                        } catch (RuntimeException e) {
                            failures.add(e);
                        }
                    }
                    log.info("使用者刪除連動：已刪除作者的草稿／待審／被駁回文章 userUuid={} eventId={} found={} deleted={} failed={}",
                            event.userUuid(), event.eventId(), targets.size(), deleted, failures.size());
                    if (!failures.isEmpty()) {
                        RuntimeException first = failures.get(0);
                        failures.stream().skip(1).forEach(first::addSuppressed);
                        throw first;
                    }
                });
    }
}
