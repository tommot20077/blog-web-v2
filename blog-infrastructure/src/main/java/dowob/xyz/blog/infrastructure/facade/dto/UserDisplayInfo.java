package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.UUID;

/**
 * 使用者顯示資訊的極簡投影，供其他模組在列表上批次解析「誰寫的」。
 *
 * <p>與 {@link AuthorInfo} 的差別：多了 {@code avatarUrl}，且<b>以 {@code uuid} 為查詢鍵</b>
 * （跨模組識別一律為 UUID，見設計文件 §0）。{@code AuthorInfo} 仍供文章、搜尋使用，
 * 其欄位可能被序列化進 ES 文件，因此刻意不改動。</p>
 *
 * <p>本投影<b>不依使用者狀態過濾</b>：停權（BANNED）或軟刪除（DELETED）的使用者仍有 uuid、暱稱與頭像，
 * 其過往的留言必須維持原樣顯示，不能因為帳號狀態改變而變成「查無使用者」。</p>
 *
 * @param uuid      使用者公開 UUID
 * @param nickname  使用者暱稱（非唯一，僅供顯示）
 * @param avatarUrl 頭像網址；使用者未設定時為 {@code null}
 *
 * @author Yuan
 * @version 1.0
 */
public record UserDisplayInfo(UUID uuid, String nickname, String avatarUrl) {}
