package dowob.xyz.blog.common.api.response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PageResult 單元測試
 *
 * <p>驗證 {@code of()} 工廠方法的分頁計算（totalPage）與欄位對應。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("PageResult 單元測試")
class PageResultTest {

    @Nested
    @DisplayName("of() 正常分頁計算測試")
    class OfNormalTest {

        @Test
        @DisplayName("33 筆資料 / pageSize=10，totalPage 應為 4（向上取整）")
        void of_33Items_pageSize10_totalPage4() {
            PageResult<String> result = PageResult.of(1, 10, 33L, Collections.emptyList());
            assertThat(result.getTotalPage()).isEqualTo(4);
        }

        @Test
        @DisplayName("30 筆資料 / pageSize=10（整除），totalPage 應為 3")
        void of_30Items_pageSize10_totalPage3() {
            PageResult<String> result = PageResult.of(1, 10, 30L, Collections.emptyList());
            assertThat(result.getTotalPage()).isEqualTo(3);
        }

        @Test
        @DisplayName("1 筆資料 / pageSize=10，totalPage 應為 1")
        void of_1Item_pageSize10_totalPage1() {
            PageResult<String> result = PageResult.of(1, 10, 1L, Collections.emptyList());
            assertThat(result.getTotalPage()).isEqualTo(1);
        }

        @Test
        @DisplayName("100 筆資料 / pageSize=10，totalPage 應為 10")
        void of_100Items_pageSize10_totalPage10() {
            PageResult<String> result = PageResult.of(1, 10, 100L, Collections.emptyList());
            assertThat(result.getTotalPage()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("of() 邊界條件測試")
    class OfBoundaryTest {

        @Test
        @DisplayName("total=0 時，totalPage 應為 0")
        void of_totalZero_totalPageIsZero() {
            PageResult<String> result = PageResult.of(1, 10, 0L, Collections.emptyList());
            assertThat(result.getTotalPage()).isEqualTo(0);
        }

        @Test
        @DisplayName("pageSize=0 時，totalPage 應為 0（防零除）")
        void of_pageSizeZero_totalPageIsZero() {
            PageResult<String> result = PageResult.of(1, 0, 100L, Collections.emptyList());
            assertThat(result.getTotalPage()).isEqualTo(0);
        }

        @Test
        @DisplayName("total=0 且 pageSize=0 時，totalPage 應為 0")
        void of_totalZeroAndPageSizeZero_totalPageIsZero() {
            PageResult<String> result = PageResult.of(1, 0, 0L, Collections.emptyList());
            assertThat(result.getTotalPage()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("of() 欄位對應測試")
    class OfFieldMappingTest {

        @Test
        @DisplayName("of() 返回的 pageNum 應與傳入值相同")
        void of_pageNumShouldMatch() {
            PageResult<String> result = PageResult.of(3, 10, 50L, Collections.emptyList());
            assertThat(result.getPageNum()).isEqualTo(3);
        }

        @Test
        @DisplayName("of() 返回的 pageSize 應與傳入值相同")
        void of_pageSizeShouldMatch() {
            PageResult<String> result = PageResult.of(1, 20, 50L, Collections.emptyList());
            assertThat(result.getPageSize()).isEqualTo(20);
        }

        @Test
        @DisplayName("of() 返回的 total 應與傳入值相同")
        void of_totalShouldMatch() {
            PageResult<String> result = PageResult.of(1, 10, 99L, Collections.emptyList());
            assertThat(result.getTotal()).isEqualTo(99L);
        }

        @Test
        @DisplayName("of() 返回的 list 應與傳入值相同")
        void of_listShouldMatch() {
            List<String> items = List.of("a", "b", "c");
            PageResult<String> result = PageResult.of(1, 10, 3L, items);
            assertThat(result.getList()).containsExactlyElementsOf(items);
        }

        @Test
        @DisplayName("of() 傳入 null list 應保留 null")
        void of_nullList_listIsNull() {
            PageResult<String> result = PageResult.of(1, 10, 0L, null);
            assertThat(result.getList()).isNull();
        }
    }
}
