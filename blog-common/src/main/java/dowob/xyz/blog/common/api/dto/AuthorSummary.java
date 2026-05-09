package dowob.xyz.blog.common.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 跨模組共享的作者輕量資訊 DTO。
 *
 * <p>用於各模組（comment / series 等）需要顯示作者基本資訊的場景，
 * 避免每個模組各自定義同樣形狀的 DTO。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthorSummary {
    private UUID uuid;
    private String nickname;
    private String avatarUrl;
}
