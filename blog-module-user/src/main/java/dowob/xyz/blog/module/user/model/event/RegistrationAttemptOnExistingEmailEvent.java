package dowob.xyz.blog.module.user.model.event;

import java.util.UUID;

/**
 * 有人嘗試以「已註冊的信箱」再次註冊事件（SEC-14／Q8）
 *
 * <p>註冊流程偵測到 email 已存在時，對外一律回與新註冊相同的成功回應（避免帳號枚舉），
 * 並在回應之後發布本事件，由 consumer 寄通知信給<b>原信箱主人</b>：「有人嘗試用你的信箱註冊；
 * 若是你本人，請直接登入或使用忘記密碼」。</p>
 *
 * <p><b>刻意不帶任何註冊請求的資料</b>（嘗試者提交的 username／nickname／密碼／IP）：
 * 信件內容只由原帳號資料組成，攻擊者無法藉由請求欄位在寄給受害者的信裡注入文字或連結。</p>
 *
 * @param eventId  事件 dedup key（發送端每次 publish 時隨機產生）
 * @param userUuid 原信箱主人的公開 UUID
 * @param email    原信箱主人的電子信箱（取自資料庫儲存值，而非請求輸入）
 * @param nickname 原信箱主人的暱稱（信件稱呼用）
 * @author Yuan
 * @version 1.0
 */
public record RegistrationAttemptOnExistingEmailEvent(UUID eventId, UUID userUuid, String email, String nickname) {
}
