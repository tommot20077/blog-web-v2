package dowob.xyz.blog.module.file.model.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 儲存配額回應 DTO
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuotaResponse {

    /**
     * 已使用空間（位元組）
     */
    private Long usedBytes;

    /**
     * 配額上限（位元組）
     */
    private Long limitBytes;

    /**
     * 剩餘可用空間（位元組）
     */
    private Long remainingBytes;
}
