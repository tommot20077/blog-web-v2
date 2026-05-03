package dowob.xyz.blog.infrastructure.idempotency.repository;

import dowob.xyz.blog.infrastructure.idempotency.model.ProcessedEvent;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

/**
 * Processed Event Repository — 提供 save 跟 UNIQUE 衝突 detect。
 *
 * @author Yuan
 * @version 1.0
 */
@Repository
public interface ProcessedEventRepository extends CrudRepository<ProcessedEvent, Long> {
}
