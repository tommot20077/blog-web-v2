package dowob.xyz.blog.module.user.model.event;

import java.util.UUID;

/**
 * 用戶密碼重設申請事件
 *
 * <p>用戶申請忘記密碼後發布至 RabbitMQ，消費方可據此發送密碼重設信。</p>
 *
 * @param eventId    事件 dedup key（發送端每次 publish 時隨機產生；舊格式 in-flight 訊息為 null）
 * @param userUuid   用戶公開 UUID（舊格式 in-flight 訊息為 null）
 * @param email      用戶電子信箱
 * @param resetToken 密碼重設 Token
 * @author Yuan
 * @version 2.0
 */
public record UserPasswordResetRequestedEvent(UUID eventId, UUID userUuid, String email, String resetToken) {
}
