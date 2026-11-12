# P0 Backend Red E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add executable backend Testcontainers red E2E tests for P0 business journeys without changing product code to make them green.

**Architecture:** Reuse the existing backend E2E foundation under `blog-start/src/test/java/dowob/xyz/blog/e2e`. Add red-suite tests under a separate package and Maven profile so intentional failures do not enter the existing blocking E2E suite.

**Tech Stack:** Java 21, Spring Boot 3.5, JUnit 5, MockMvc, Testcontainers, PostgreSQL, Redis, RabbitMQ, MinIO, Elasticsearch, PowerShell.

---

## Red-Test Contract

This plan stops at red.

- Tests must compile.
- Tests must start Spring and Testcontainers.
- Tests must reach assertions.
- Failures must identify product behavior, contract drift, or missing integration behavior.
- Do not change backend runtime code to make these tests green.
- Always write command output under `logs/`.

## File Structure

Create:

- `blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0AuthLifecycleRedE2E.java`
- `blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0AuthorReviewRedE2E.java`
- `blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0ReaderInteractionRedE2E.java`

Modify:

- `blog-start/pom.xml`

Reuse:

- `blog-start/src/test/java/dowob/xyz/blog/e2e/config/AbstractE2ETest.java`
- `blog-start/src/test/java/dowob/xyz/blog/e2e/support/AuthHelper.java`
- `blog-start/src/test/java/dowob/xyz/blog/e2e/support/DataBuilder.java`
- `blog-start/src/test/java/dowob/xyz/blog/e2e/support/DatabaseCleaner.java`
- `blog-start/src/test/java/dowob/xyz/blog/e2e/support/E2EAssertions.java`

## Task 1: Isolate Backend Red E2E Profile

**Files:**

- Modify: `blog-start/pom.xml`

- [ ] **Step 1: Add a `red-e2e` Maven profile**

Add this profile after the existing `e2e` profile:

```xml
<profile>
    <id>red-e2e</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <includes>
                        <include>**/red/*RedE2E.java</include>
                    </includes>
                    <excludes combine.self="override"/>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

- [ ] **Step 2: Verify the profile has no tests yet**

Run:

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\mvnw -B test -Pred-e2e -pl blog-start -am --no-transfer-progress 2>&1 | Tee-Object -FilePath logs\backend-red-e2e-empty-profile.log
```

Expected:

- Build succeeds.
- No `*RedE2E` tests are discovered yet.

- [ ] **Step 3: Commit profile isolation**

```powershell
git status --short
git add blog-start/pom.xml
git commit -m "test(e2e): 隔離後端紅燈 e2e profile"
```

## Task 2: Auth Lifecycle Red E2E

**Files:**

- Create: `blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0AuthLifecycleRedE2E.java`

- [ ] **Step 1: Write the executable red test**

Create the file:

```java
package dowob.xyz.blog.e2e.red;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("P0 紅燈 - Auth 生命週期")
class P0AuthLifecycleRedE2E extends AbstractE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("註冊驗證登入 refresh 登出應維持 token 與使用者狀態一致")
    void authLifecycleShouldKeepTokenAndUserStateConsistent() throws Exception {
        String email = "p0-auth-red@test.local";
        String password = "AuthRed123!";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DataBuilder.register(email, password, "p0authred", "P0 Auth Red"))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        String token = jdbcTemplate.queryForObject(
                "SELECT vt.token FROM verification_tokens vt JOIN users u ON u.id = vt.user_id " +
                        "WHERE u.email = ? AND vt.type = 'EMAIL_VERIFICATION'",
                String.class,
                email
        );

        mockMvc.perform(get("/api/v1/auth/verify-email").param("token", token))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.login(email, password))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        JsonNode loginData = objectMapper.readTree(login.getResponse().getContentAsString()).get("data");
        assertThat(loginData.path("accessToken").asText()).isNotBlank();

        String refreshCookie = login.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.contains("refresh"))
                .findFirst()
                .orElse("");
        assertThat(refreshCookie).as("refresh cookie should be issued to browser clients").isNotBlank();

        mockMvc.perform(post("/api/v1/auth/refresh").header("Cookie", refreshCookie))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/logout").header("Cookie", refreshCookie))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        mockMvc.perform(post("/api/v1/auth/refresh").header("Cookie", refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("重複註冊 錯密碼 無 refresh cookie 應回傳穩定錯誤契約")
    void authNegativeCasesShouldUseStableErrorEnvelope() throws Exception {
        String email = "p0-auth-negative-red@test.local";
        String password = "AuthRed123!";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DataBuilder.register(email, password, "p0authneg", "P0 Auth Negative"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DataBuilder.register(email, password, "p0authneg2", "P0 Auth Negative 2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.login(email, "Wrong123!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
```

- [ ] **Step 2: Run and capture meaningful red**

Run:

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\mvnw -B test -Pred-e2e -pl blog-start -Dtest=P0AuthLifecycleRedE2E --no-transfer-progress 2>&1 | Tee-Object -FilePath logs\p0-auth-lifecycle-red.log
```

Expected:

- Test compiles and starts Testcontainers.
- If it fails, failure is an assertion/status/contract mismatch, not a harness failure.

- [ ] **Step 3: Commit the red test**

```powershell
git status --short
git add blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0AuthLifecycleRedE2E.java
git commit -m "test(e2e): 新增 auth 生命週期紅燈測試"
```

## Task 3: Author Review Red E2E

**Files:**

- Create: `blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0AuthorReviewRedE2E.java`

- [ ] **Step 1: Write the executable red test**

Create the file:

```java
package dowob.xyz.blog.e2e.red;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("P0 紅燈 - Author 送審與 Admin 審核")
class P0AuthorReviewRedE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("Author 建草稿送審 Admin 發布後公開列表與搜尋應可見")
    void publishFlowShouldMakeArticleVisibleToReaderAndSearch() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "p0-admin-red@test.local", "Admin123!", "p0adminred", "P0 Admin Red", Role.ADMIN);
        String authorToken = authHelper.createUserWithRole(
                "p0-author-red@test.local", "Author123!", "p0authorred", "P0 Author Red", Role.AUTHOR);

        String categoryResponse = mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.category("P0 Red", "p0-red")))
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String categoryUuid = objectMapper.readTree(categoryResponse).path("data").path("uuid").asText();

        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", "P0 backend red publish journey");
        article.put("summary", "P0 backend red summary");
        article.put("content", "P0 backend red content");
        article.put("categoryIds", java.util.List.of(categoryUuid));
        article.put("tagNames", java.util.List.of("p0-red", "backend-red"));

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(article))
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode articleData = objectMapper.readTree(createResponse).path("data");
        String articleUuid = articleData.path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish")
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].title").value(org.hamcrest.Matchers.hasItem("P0 backend red publish journey")));

        mockMvc.perform(get("/api/v1/search").param("q", "backend red publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[*].title").value(org.hamcrest.Matchers.hasItem("P0 backend red publish journey")));
    }

    @Test
    @DisplayName("Admin 退回後 Author 應看到退回原因且可重新送審")
    void rejectFlowShouldExposeReasonAndAllowResubmit() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "p0-reject-admin@test.local", "Admin123!", "p0rejectadmin", "P0 Reject Admin", Role.ADMIN);
        String authorToken = authHelper.createUserWithRole(
                "p0-reject-author@test.local", "Author123!", "p0rejectauthor", "P0 Reject Author", Role.AUTHOR);

        Map<String, Object> article = new LinkedHashMap<>();
        article.put("title", "P0 reject and resubmit red");
        article.put("content", "content before rejection");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(article))
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String articleUuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "請補充測試證據")))
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(get("/api/v1/articles/me")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("請補充測試證據")));

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    }
}
```

- [ ] **Step 2: Run and capture meaningful red**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\mvnw -B test -Pred-e2e -pl blog-start -Dtest=P0AuthorReviewRedE2E --no-transfer-progress 2>&1 | Tee-Object -FilePath logs\p0-author-review-red.log
```

Expected:

- Failure, if any, is a product assertion such as search visibility, rejection reason shape, or status transition.

- [ ] **Step 3: Commit the red test**

```powershell
git status --short
git add blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0AuthorReviewRedE2E.java
git commit -m "test(e2e): 新增 author 審核流程紅燈測試"
```

## Task 4: Reader Interaction Red E2E

**Files:**

- Create: `blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0ReaderInteractionRedE2E.java`

- [ ] **Step 1: Write the executable red test**

Create the file:

```java
package dowob.xyz.blog.e2e.red;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("P0 紅燈 - Reader 互動")
class P0ReaderInteractionRedE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("Reader 應可按讚收藏留言回覆且操作具 idempotency")
    void readerInteractionsShouldBeConsistentAndIdempotent() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "p0-reader-admin@test.local", "Admin123!", "p0readeradmin", "P0 Reader Admin", Role.ADMIN);
        String authorToken = authHelper.createUserWithRole(
                "p0-reader-author@test.local", "Author123!", "p0readerauthor", "P0 Reader Author", Role.AUTHOR);
        String readerToken = authHelper.createUserWithRole(
                "p0-reader@test.local", "Reader123!", "p0reader", "P0 Reader", Role.USER);

        String categoryResponse = mockMvc.perform(post("/api/v1/admin/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.category("Reader Red", "reader-red")))
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String categoryUuid = objectMapper.readTree(categoryResponse).path("data").path("uuid").asText();

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "P0 reader interaction red",
                                "content", "content for reader interaction",
                                "categoryIds", java.util.List.of(categoryUuid),
                                "tagNames", java.util.List.of("reader-red"))))
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String articleUuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish")
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/like")
                        .with(AuthHelper.bearerToken(readerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/like")
                        .with(AuthHelper.bearerToken(readerToken)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/bookmark")
                        .with(AuthHelper.bearerToken(readerToken)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/bookmark")
                        .with(AuthHelper.bearerToken(readerToken)))
                .andExpect(status().isOk());

        String commentResponse = mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "reader top comment")))
                        .with(AuthHelper.bearerToken(readerToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode commentData = objectMapper.readTree(commentResponse).path("data");
        String commentUuid = commentData.path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "content", "reader reply comment",
                                "parentUuid", commentUuid)))
                        .with(AuthHelper.bearerToken(readerToken)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                        .with(AuthHelper.bearerToken(readerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].uuid").value(articleUuid));

        mockMvc.perform(get("/api/v1/articles/" + articleUuid + "/comments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("Guest 不可執行 reader 互動且錯誤契約穩定")
    void guestInteractionsShouldBeRejectedWithStableEnvelope() throws Exception {
        mockMvc.perform(post("/api/v1/articles/missing-uuid/like"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(post("/api/v1/articles/missing-uuid/bookmark"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
```

- [ ] **Step 2: Run and capture meaningful red**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\mvnw -B test -Pred-e2e -pl blog-start -Dtest=P0ReaderInteractionRedE2E --no-transfer-progress 2>&1 | Tee-Object -FilePath logs\p0-reader-interaction-red.log
```

Expected:

- Failure, if any, is a real product or contract failure for idempotency, bookmark response shape, or comment tree behavior.

- [ ] **Step 3: Commit the red test**

```powershell
git status --short
git add blog-start/src/test/java/dowob/xyz/blog/e2e/red/P0ReaderInteractionRedE2E.java
git commit -m "test(e2e): 新增 reader 互動紅燈測試"
```

## Task 5: P0 Backend Red Suite Run

**Files:**

- No new files.

- [ ] **Step 1: Run the isolated red suite**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\mvnw -B test -Pred-e2e -pl blog-start -am --no-transfer-progress 2>&1 | Tee-Object -FilePath logs\p0-backend-red-suite.log
```

Expected:

- The suite executes the `red` package tests.
- The suite may fail.
- Failures are meaningful red failures, not compile or container startup failures.

- [ ] **Step 2: Inspect Surefire reports before rerun**

```powershell
Get-ChildItem blog-start\target\surefire-reports\TEST-dowob.xyz.blog.e2e.red*.xml |
  Select-Object FullName, Length, LastWriteTime
```

Expected:

- XML reports exist for red tests that ran.

- [ ] **Step 3: Run diff check**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
git diff --check 2>&1 | Tee-Object -FilePath logs\p0-backend-red-diff-check.log
```

Expected:

- Exit code `0`.

- [ ] **Step 4: Commit final verification note if docs are added**

Only commit if a verification note file is created during execution:

```powershell
git status --short
git add docs/superpowers/plans/2026-05-23-p0-backend-red-e2e-plan.md
git commit -m "docs(e2e): 補充後端紅燈測試執行計畫"
```

## Final Acceptance

- `red-e2e` profile exists and is isolated.
- P0 backend red tests compile and start.
- Failure logs are saved under `logs/`.
- Surefire XML reports exist.
- Product code is not changed to pass the red tests.
- Existing `e2e` profile remains separate from `red-e2e`.
