package dowob.xyz.blog.module.version.model.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 更新 version 偏好請求；所有欄位 optional，僅當非 null 才更新。
 *
 * <p>各欄位範圍對齊 Validation：
 * <ul>
 *   <li>retain: [1, 300]</li>
 *   <li>intervalSeconds: [10, 600] 上限 10 分鐘</li>
 *   <li>diffChars: [0, 5000]，0 = disabled（純時間觸發）</li>
 * </ul></p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
public class UpdatePreferenceRequest {
    private Boolean enabled;

    @Min(1)
    @Max(300)
    private Integer retain;

    @Min(10)
    @Max(600)
    private Integer intervalSeconds;

    @Min(0)
    @Max(5000)
    private Integer diffChars;
}
