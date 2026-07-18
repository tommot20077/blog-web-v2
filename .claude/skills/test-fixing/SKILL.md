---
name: test-fixing
description: Run tests and systematically fix all failing tests using smart error grouping. Use when user asks to fix failing tests, mentions test failures, runs test suite and failures occur, or requests to make tests pass.
---

# Test Fixing

Systematically identify and fix all failing tests using smart grouping strategies.

## When to Use

- Explicitly asks to fix tests ("fix these tests", "make tests pass")
- Reports test failures ("tests are failing", "test suite is broken")
- Completes implementation and wants tests passing
- Mentions CI/CD failures due to tests

## Systematic Approach

### 1. Initial Test Run

Run the full Maven test suite to identify all failing tests:

```bash
./mvnw test -pl blog-infrastructure,blog-module-user,blog-module-article -am --no-transfer-progress
```

Analyze output for:
- Total number of failures
- Error types and patterns (compilation errors, Spring context failures, assertion failures)
- Affected modules/classes

### 2. Smart Error Grouping

Group similar failures by:
- **Error type**: Compilation errors, Spring context failures, Assertion failures, Mock/stub mismatches, NullPointerException / logic errors
- **Module/class**: Same class causing multiple test failures
- **Root cause**: Missing beans, API changes, refactoring impacts

Common Java/Spring failure categories:

| Category | Indicators |
|----------|-----------|
| **Compilation errors** | `cannot find symbol`, type mismatch, syntax errors — no tests can run |
| **Spring context failures** | `NoSuchBeanDefinitionException`, missing `@MockBean` / `@MockitoBean`, misconfigured `@Import` |
| **Assertion failures** | `AssertionError`, `expected:<...> but was:<...>` |
| **Mock/stub mismatches** | `UnnecessaryStubbingException`, unexpected method invocations, `RabbitTemplate` overload ambiguity |
| **NullPointerException / logic errors** | Business logic bugs, uninitialized fields |

Prioritize groups by:
- Dependency order (compilation → context → mocks → assertions)
- Number of affected tests (highest impact first within the same tier)

### 3. Systematic Fixing Process

For each group (starting with highest impact):

1. **Identify root cause**
    - Read relevant code
    - Check recent changes with `git diff`
    - Understand the error pattern

2. **Implement fix**
    - Use Edit tool for code changes
    - Follow project conventions (see CLAUDE.md)
    - Make minimal, focused changes

3. **Verify fix**
    - Run subset of tests for this group using `-Dtest=` syntax:
      ```bash
      # Single test class
      ./mvnw test -pl <module> -Dtest=ClassName --no-transfer-progress

      # Single test method
      ./mvnw test -pl <module> -Dtest=ClassName#methodName --no-transfer-progress

      # Pattern filter
      ./mvnw test -pl <module> -Dtest="*Article*" --no-transfer-progress
      ```
    - Ensure group passes before moving on

4. **Move to next group**

### 4. Fix Order Strategy

**Infrastructure first — Compilation errors:**
- Syntax / type errors that prevent any tests from compiling
- Fix these before anything else; nothing runs until compilation succeeds

**Then configuration — Spring context failures:**
- Missing `@Bean` definitions
- Missing `@MockBean` / `@MockitoBean` in test classes
- Wrong `@Import` or security config not loaded

**Then API changes — Mock/stub mismatches:**
- Mockito stubs that no longer match updated method signatures
- `UnnecessaryStubbingException` from stubs that became irrelevant
- `RabbitTemplate.convertAndSend` overload ambiguity (cast with `(Object) any()`)

**Finally, logic — Assertion failures & NullPointerException:**
- Business logic bugs surfaced by tests
- Edge case handling
- Unexpected `null` values in domain logic

### 5. Final Verification

After all groups are fixed, run the complete test suite to confirm no regressions:

```bash
./mvnw test -pl blog-infrastructure,blog-module-user,blog-module-article -am --no-transfer-progress
```

Verify:
- All previously failing tests now pass
- No new failures introduced
- Test output is clean (no unexpected warnings or stack traces)

## Best Practices

- Fix one group at a time
- Run focused tests after each fix using `-Dtest=`
- Use `git diff` to understand recent changes
- Look for patterns in failures
- Don't move to next group until current passes
- Keep changes minimal and focused

## Example Workflow

Yuan: "測試在我重構之後都壞掉了"

1. Run full suite → 15 failures identified
2. Group errors:
    - 3 Compilation errors (`cannot find symbol` — class renamed)
    - 5 Spring context failures (missing `@MockitoBean` after new dependency added)
    - 4 Mock/stub mismatches (`UnnecessaryStubbingException` after service signature changed)
    - 3 Assertion failures (logic bug in status transition)
3. Fix compilation errors first:
    ```bash
    ./mvnw test -pl blog-module-article -Dtest="*Article*" --no-transfer-progress
    ```
4. Fix Spring context failures → Run subset → Verify:
    ```bash
    ./mvnw test -pl blog-module-article -Dtest=ArticleControllerTest --no-transfer-progress
    ```
5. Fix mock/stub mismatches → Run subset → Verify:
    ```bash
    ./mvnw test -pl blog-module-article -Dtest=ArticleServiceTest --no-transfer-progress
    ```
6. Fix assertion failures → Run subset → Verify:
    ```bash
    ./mvnw test -pl blog-module-article -Dtest=ArticleStatusTest --no-transfer-progress
    ```
7. Run full suite → All pass ✓
