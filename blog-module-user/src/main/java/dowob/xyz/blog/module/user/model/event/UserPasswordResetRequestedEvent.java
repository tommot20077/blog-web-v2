package dowob.xyz.blog.module.user.model.event;

/**
 * 用戶密碼重設申請事件
 *
 * <p>用戶申請忘記密碼後發布至 RabbitMQ，消費方可據此發送密碼重設信。</p>
 *
 * @param userId     用戶 ID
 * @param email      用戶電子信箱
 * @param resetToken 密碼重設 Token
 * @author Yuan
 * @version 1.0
 */
public record UserPasswordResetRequestedEvent(Long userId, String email, String resetToken) {
}
