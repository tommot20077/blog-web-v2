package dowob.xyz.blog.module.user.model.event;

/**
 * 用戶註冊事件
 *
 * <p>用戶完成註冊後發布至 RabbitMQ，消費方可據此發送驗證信。</p>
 *
 * @param userId            用戶 ID
 * @param email             用戶電子信箱
 * @param nickname          用戶暱稱
 * @param verificationToken 電子信箱驗證 Token
 * @author Yuan
 * @version 1.0
 */
public record UserRegisteredEvent(Long userId, String email, String nickname, String verificationToken) {
}
