package dowob.xyz.blog.module.user.model.event;

import java.util.UUID;

/**
 * 用戶註冊事件
 *
 * <p>用戶完成註冊（或重發驗證信）後發布至 RabbitMQ，由 {@code EmailVerificationConsumer} 據此寄送驗證信。</p>
 *
 * <p><b>事件不含個資與 token（ARCH-24）</b>：不帶 email、暱稱、驗證 token、驗證碼。
 * consumer 寄信當下以 {@code userUuid} 自行查詢——email／暱稱查 {@code users}、驗證碼查 Redis——並在此時
 * <b>產生</b>驗證 token（A1：{@code verification_tokens} 只存其 SHA-256，信件才是明文）。
 * 如此失敗重試耗盡進入無 TTL 的 DLQ 時，佇列裡不會留下個資或可直接使用的憑證。</p>
 *
 * @param eventId  事件 dedup key（發送端每次 publish 時隨機產生）
 * @param userUuid 用戶公開 UUID（consumer 查詢收件資料的唯一依據）
 * @author Yuan
 * @version 3.0
 */
public record UserRegisteredEvent(UUID eventId, UUID userUuid) {
}
