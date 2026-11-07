package dowob.xyz.blog.module.user.model.event;

import java.util.UUID;

/**
 * 用戶密碼重設申請事件
 *
 * <p>用戶申請忘記密碼後發布至 RabbitMQ，由 {@code PasswordResetConsumer} 據此寄送密碼重設信。</p>
 *
 * <p><b>事件不含個資與 token（ARCH-24）</b>：不帶 email 與重設 token。consumer 寄信當下以 {@code userUuid}
 * 查詢 email（{@code users}），並在此時<b>產生</b>重設 token（A1：{@code verification_tokens} 只存其 SHA-256，
 * 信件才是明文）。重設 token 可直接完成密碼重設，因此不得進入 durable queue 與 DLQ。</p>
 *
 * @param eventId  事件 dedup key（發送端每次 publish 時隨機產生）
 * @param userUuid 用戶公開 UUID（consumer 查詢收件資料的唯一依據）
 * @author Yuan
 * @version 3.0
 */
public record UserPasswordResetRequestedEvent(UUID eventId, UUID userUuid) {
}
