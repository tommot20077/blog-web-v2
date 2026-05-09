package dowob.xyz.blog.module.reading.model.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProgressResponse {
    private BigDecimal progress;
    private String lastHeading;
    private LocalDateTime updatedAt;
}
