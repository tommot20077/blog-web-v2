package dowob.xyz.blog.architecture.fixture.openapi;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * {@code OpenApiAnnotationGuardTest} 的反向驗證 fixture——<b>含故意違規</b>（缺 {@code @Operation}、summary 空白、
 * {@code @ResponseStatus} 無對應 {@code @ApiResponse}），不得被當成範例。
 *
 * @author Yuan
 * @version 1.0
 */
@Tag(name = "Fixture")
public class OpenApiAnnotationFixture {

    /** 違規：GET 缺 @Operation */
    @GetMapping("/f/get")
    public void missingGet() {
    }

    /** 違規：POST 缺 @Operation */
    @PostMapping("/f/post")
    public void missingPost() {
    }

    /** 違規：PUT 缺 @Operation */
    @PutMapping("/f/put")
    public void missingPut() {
    }

    /** 違規：PATCH 缺 @Operation */
    @PatchMapping("/f/patch")
    public void missingPatch() {
    }

    /** 違規：DELETE 缺 @Operation */
    @DeleteMapping("/f/delete")
    public void missingDelete() {
    }

    /** 違規：@RequestMapping(method=...) 缺 @Operation */
    @RequestMapping(value = "/f/rm", method = RequestMethod.POST)
    public void missingRequestMapping() {
    }

    /** 違規：summary 空白 */
    @GetMapping("/f/blank")
    @Operation(summary = "  ")
    public void blankSummary() {
    }

    /** 違規：@ResponseStatus 202 卻沒有對應 @ApiResponse */
    @PostMapping("/f/accepted-missing")
    @Operation(summary = "非 200 成功碼未標示")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void statusWithoutApiResponse() {
    }

    /** 合規：有 summary */
    @GetMapping("/f/ok")
    @Operation(summary = "合規")
    public void ok() {
    }

    /** 合規：@ResponseStatus 202 且有 @ApiResponse 202 */
    @PostMapping("/f/accepted-ok")
    @Operation(summary = "合規非 200")
    @ApiResponse(responseCode = "202", description = "已接受")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void statusWithApiResponse() {
    }

    /** 合規：非 handler 的一般方法不在範圍 */
    public void helper() {
    }

    /** 違規：類別缺 @Tag 的 fixture */
    public static class NoTag {

        /** 合規 handler，但類別沒有 @Tag */
        @GetMapping("/f/notag")
        @Operation(summary = "合規")
        public void ok() {
        }
    }
}
