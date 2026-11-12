package dowob.xyz.blog.architecture.fixture.ratelimit;

import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * {@code RateLimitedEndpointGuardTest} 的反向驗證 fixture——<b>含故意違規</b>（未標 {@code @RateLimited} 的寫入 handler），
 * 不得被當成範例。證明守衛抓得到各種寫法，也不誤判已標註、讀取與 DELETE。
 *
 * @author Yuan
 * @version 1.0
 */
public class RateLimitWriteFixture {

    /** 違規：POST 未標註 */
    @PostMapping("/f/post")
    public void unmarkedPost() {
    }

    /** 違規：PUT 未標註 */
    @PutMapping("/f/put")
    public void unmarkedPut() {
    }

    /** 違規：PATCH 未標註 */
    @PatchMapping("/f/patch")
    public void unmarkedPatch() {
    }

    /** 違規：@RequestMapping(method = POST) 未標註 */
    @RequestMapping(value = "/f/rm", method = RequestMethod.POST)
    public void unmarkedRequestMappingPost() {
    }

    /** 合規：方法層標註 */
    @PostMapping("/f/ok")
    @RateLimited("fixture")
    public void markedPost() {
    }

    /** 合規：DELETE 不在範圍 */
    @RequestMapping(value = "/f/del", method = RequestMethod.DELETE)
    public void delete() {
    }

    /** 合規：GET 不在範圍 */
    @RequestMapping(value = "/f/get", method = RequestMethod.GET)
    public void read() {
    }

    /** 類別層標註的 fixture */
    @RateLimited("fixture")
    public static class ClassLevelMarked {

        /** 合規：類別層標註涵蓋 */
        @PostMapping("/f/class")
        public void post() {
        }
    }
}
