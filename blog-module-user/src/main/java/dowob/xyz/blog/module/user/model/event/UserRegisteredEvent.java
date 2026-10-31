package dowob.xyz.blog.module.user.model.event;

import java.util.UUID;

/**
 * 用戶註冊事件
 *
 * <p>用戶完成註冊（或重發驗證信）後發布至 RabbitMQ，消費方可據此發送驗證信。</p>
 *
 * @param eventId           事件 dedup key（發送端每次 publish 時隨機產生；舊格式 in-flight 訊息為 null）
 * @param userUuid          用戶公開 UUID（舊格式 in-flight 訊息為 null）
 * @param email             用戶電子信箱
 * @param nickname          用戶暱稱
 * @param verificationToken 電子信箱驗證 Token
 * @param verificationCode  電子信箱 6 位數驗證碼
 * @author Yuan
 * @version 2.0
 */
public record UserRegisteredEvent(UUID eventId, UUID userUuid, String email, String nickname,
                                  String verificationToken, String verificationCode) {
}
