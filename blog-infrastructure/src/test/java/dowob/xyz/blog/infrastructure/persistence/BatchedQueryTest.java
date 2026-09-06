package dowob.xyz.blog.infrastructure.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BatchedQuery 單元測試
 *
 * <p>
 * 驗證無上界輸入被切成安全批次、批次邊界不多發空查詢、結果串接後與未切批等價。
 * 純 JUnit 單元測試，不啟動 Spring Context、不碰資料庫——受測目標是切批演算法本身，
 * 查詢函式以 lambda 模擬並記錄每批實際收到的大小。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("BatchedQuery 單元測試")
class BatchedQueryTest {

    /** 每次查詢函式被呼叫時收到的批次大小，依呼叫順序記錄 */
    private List<Integer> observedBatchSizes;

    /** 模擬的查詢函式：記錄批次大小，並把每個 id 映射成字串結果 */
    private Function<List<Integer>, List<String>> recordingQuery;

    @BeforeEach
    void setUp() {
        observedBatchSizes = new ArrayList<>();
        recordingQuery = batch -> {
            observedBatchSizes.add(batch.size());
            return batch.stream().map(id -> "v" + id).toList();
        };
    }

    /**
     * 產生 1..count 的連續 id 清單。
     *
     * @param count 筆數
     * @return id 清單
     */
    private List<Integer> ids(int count) {
        return IntStream.rangeClosed(1, count).boxed().toList();
    }

    @Test
    @DisplayName("輸入為 null 時回傳空清單，且完全不發查詢")
    void whenInputIsNull_returnsEmptyAndNeverQueries() {
        List<String> result = BatchedQuery.queryInBatches(null, recordingQuery);

        assertThat(result).isEmpty();
        assertThat(observedBatchSizes).isEmpty();
    }

    @Test
    @DisplayName("輸入為空集合時回傳空清單，且完全不發查詢（避免產生 IN ()）")
    void whenInputIsEmpty_returnsEmptyAndNeverQueries() {
        List<String> result = BatchedQuery.queryInBatches(List.of(), recordingQuery);

        assertThat(result).isEmpty();
        assertThat(observedBatchSizes).isEmpty();
    }

    @Test
    @DisplayName("輸入小於批次上限時只發一次查詢，且該批含全部輸入")
    void whenInputSmallerThanBatchSize_queriesOnceWithWholeInput() {
        List<String> result = BatchedQuery.queryInBatches(ids(3), recordingQuery);

        assertThat(observedBatchSizes).containsExactly(3);
        assertThat(result).containsExactly("v1", "v2", "v3");
    }

    @Test
    @DisplayName("輸入剛好等於批次上限時只發一次查詢，不得多發一次空批")
    void whenInputExactlyBatchSize_queriesOnceNotTwice() {
        List<String> result = BatchedQuery.queryInBatches(ids(BatchedQuery.BATCH_SIZE), recordingQuery);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE);
        assertThat(result).hasSize(BatchedQuery.BATCH_SIZE);
    }

    @Test
    @DisplayName("輸入剛好等於批次上限兩倍時只發兩次查詢，不得多發一次空批")
    void whenInputExactlyTwiceBatchSize_queriesTwiceNotThrice() {
        int total = BatchedQuery.BATCH_SIZE * 2;

        List<String> result = BatchedQuery.queryInBatches(ids(total), recordingQuery);

        assertThat(observedBatchSizes)
                .containsExactly(BatchedQuery.BATCH_SIZE, BatchedQuery.BATCH_SIZE);
        assertThat(result).hasSize(total);
    }

    @Test
    @DisplayName("輸入超過批次上限時依序切批，最後一批為餘數")
    void whenInputExceedsBatchSize_splitsWithRemainderLast() {
        int total = BatchedQuery.BATCH_SIZE + 100;

        BatchedQuery.queryInBatches(ids(total), recordingQuery);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 100);
    }

    @Test
    @DisplayName("切批後的結果與未切批逐筆映射完全等價，且順序一致")
    void whenSplitIntoBatches_resultEqualsUnbatchedAndKeepsOrder() {
        int total = BatchedQuery.BATCH_SIZE + 100;
        List<String> expected = ids(total).stream().map(id -> "v" + id).toList();

        List<String> result = BatchedQuery.queryInBatches(ids(total), recordingQuery);

        assertThat(result).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("某一批查無資料時不影響其他批，結果只串接有資料的批")
    void whenOneBatchReturnsNothing_concatenatesRemainingBatches() {
        int total = BatchedQuery.BATCH_SIZE + 2;
        Function<List<Integer>, List<String>> firstBatchEmpty = batch -> {
            observedBatchSizes.add(batch.size());
            return batch.size() == BatchedQuery.BATCH_SIZE
                    ? List.of()
                    : batch.stream().map(id -> "v" + id).toList();
        };

        List<String> result = BatchedQuery.queryInBatches(ids(total), firstBatchEmpty);

        assertThat(observedBatchSizes).containsExactly(BatchedQuery.BATCH_SIZE, 2);
        assertThat(result).containsExactly("v" + (BatchedQuery.BATCH_SIZE + 1),
                                           "v" + (BatchedQuery.BATCH_SIZE + 2));
    }

    @Test
    @DisplayName("批次上限與既有 PUBLISHED_FILTER_BATCH_SIZE 對齊為 500")
    void batchSizeAlignsWithExistingConstant() {
        assertThat(BatchedQuery.BATCH_SIZE).isEqualTo(500);
    }
}
