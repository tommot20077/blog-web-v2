package dowob.xyz.blog.module.version.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Effective version config response。每個欄位含 value + source 給前端標示來源。
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EffectiveConfigResponse {
    private Field<Boolean> enabled;
    private Field<Integer> retain;
    private Field<Integer> intervalSeconds;
    private Field<Integer> diffChars;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Field<T> {
        private T value;
        /** "user" — 來自 user_preferences override；"system" — 來自 application.yaml fallback */
        private String source;
    }
}
