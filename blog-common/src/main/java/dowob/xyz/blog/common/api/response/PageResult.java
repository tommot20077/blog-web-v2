package dowob.xyz.blog.common.api.response;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分頁數據封裝類，用於統一 API 分頁響應結構
 *
 * @param <T> 數據類型
 *
 * @author Yuan
 * @version 1.0
 */
@Data
@NoArgsConstructor
public class PageResult<T> {

    /**
     * 當前頁碼
     */
    private Integer current;

    /**
     * 每頁數量
     */
    private Integer size;

    /**
     * 總頁數
     */
    private Integer pages;

    /**
     * 總條數
     */
    private Long total;

    /**
     * 數據列表
     */
    private List<T> records;


    /**
     * 將原始數據轉換為分頁封裝對象
     *
     * @param current 當前頁碼
     * @param size    每頁數量
     * @param total   總條數
     * @param records 數據列表
     * @param <T>     數據類型
     *
     * @return 分頁結果對象
     */
    public static <T> PageResult<T> of(Integer current, Integer size, Long total, List<T> records) {
        PageResult<T> result = new PageResult<>();
        result.setCurrent(current);
        result.setSize(size);
        result.setTotal(total);
        result.setRecords(records);
        /** 計算總頁數 */
        int totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        result.setPages(totalPages);
        return result;
    }
}
