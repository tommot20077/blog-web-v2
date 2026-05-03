package dowob.xyz.blog.infrastructure.idempotency.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ event 冪等記錄（processed_events 表映射）。
 *
 * <p>UNIQUE (event_id, consumer_name) — 一個 event 多 consumer 訂閱時各自獨立冪等。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("processed_events")
public class ProcessedEvent {
    @Id
    private Long id;

    @Column("event_id")
    private UUID eventId;

    @Column("consumer_name")
    private String consumerName;

    @CreatedDate
    @Column("processed_at")
    private LocalDateTime processedAt;
}
