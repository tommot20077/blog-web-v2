package dowob.xyz.blog.module.user.model.event;

import java.util.UUID;

/**
 * 有人嘗試以「已註冊的信箱」再次註冊事件（SEC-14／Q8）
 *
 * <p>註冊流程偵測到 email 已存在時，回應與新註冊相同的成功（消除直接回錯誤碼的零成本列舉；仍有需 2 次請求的副作用列舉，見 findings SEC-14 殘餘），
 * 並在回應之後發布本事件，由 consumer 寄通知信給<b>原信箱主人</b>：「有人嘗試用你的信箱註冊；
 * 若是你本人，請直接登入或使用忘記密碼」。</p>
 *
 * <p><b>事件不含個資（ARCH-24）</b>：不帶 email 與暱稱，consumer 寄信當下以 {@code userUuid} 查 {@code users}。
 * 也<b>刻意不帶任何註冊請求的資料</b>（嘗試者提交的 username／nickname／密碼／IP）：
 * 信件內容只由原帳號資料組成，攻擊者無法藉由請求欄位在寄給受害者的信裡注入文字或連結。</p>
 *
 * @param eventId  事件 dedup key（發送端每次 publish 時隨機產生）
 * @param userUuid 原信箱主人的公開 UUID
 * @author Yuan
 * @version 2.0
 */
public record RegistrationAttemptOnExistingEmailEvent(UUID eventId, UUID userUuid) {
}
