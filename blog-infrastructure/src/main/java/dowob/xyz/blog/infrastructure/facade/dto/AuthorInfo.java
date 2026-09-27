package dowob.xyz.blog.infrastructure.facade.dto;

import java.util.UUID;

/**
 * 文章作者的極簡投影，供列表頁批次解析作者使用。
 *
 * <p>只含列表卡片渲染作者所需的兩個欄位。刻意不回傳整個使用者實體：
 * 逐筆 {@code findById} 會連 {@code password_hash} 等欄位一併撈出（PERF-02）。</p>
 *
 * @param uuid     作者公開 UUID
 * @param nickname 作者暱稱（非唯一，僅供顯示；識別作者一律用 {@code uuid}）
 *
 * @author Yuan
 * @version 1.0
 */
public record AuthorInfo(UUID uuid, String nickname) {}
