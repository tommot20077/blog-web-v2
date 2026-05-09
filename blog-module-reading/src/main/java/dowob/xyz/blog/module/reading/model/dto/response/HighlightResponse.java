package dowob.xyz.blog.module.reading.model.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class HighlightResponse {
    private UUID uuid;
    private String snippet;
    private String prefix;
    private String suffix;
    private String color;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
