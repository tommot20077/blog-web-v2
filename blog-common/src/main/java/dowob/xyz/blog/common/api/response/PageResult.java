package dowob.xyz.blog.common.api.response;

import lombok.AllArgsConstructor;
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
@AllArgsConstructor
public class PageResult<T> {

    /**
     * 當前頁碼
     */
    private Integer pageNum;

    /**
     * 每頁數量
     */
    private Integer pageSize;

    /**
     * 總頁數
     */
    private Integer totalPage;

    /**
     * 總條數
     */
    private Long total;

    /**
     * 數據列表
     */
    private List<T> list;


    /**
     * 將原始數據轉換為分頁封裝對象
     *
     * @param pageNum  當前頁碼
     * @param pageSize 每頁數量
     * @param total    總條數
     * @param list     數據列表
     * @param <T>      數據類型
     *
     * @return 分頁結果對象
     */
    public static <T> PageResult<T> of(Integer pageNum, Integer pageSize, Long total, List<T> list) {
        PageResult<T> result = new PageResult<>();
        result.setPageNum(pageNum);
        result.setPageSize(pageSize);
        result.setTotal(total);
        result.setList(list);
        /** 計算總頁數 */
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) total / pageSize) : 0;
        result.setTotalPage(totalPages);
        return result;
    }
}
