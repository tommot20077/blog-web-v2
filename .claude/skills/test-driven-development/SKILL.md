---
name: test-driven-development
description: Use when implementing any feature or bugfix, before writing implementation code
---

# Test-Driven Development (TDD)

## The Iron Law

```
NO PRODUCTION CODE WITHOUT A FAILING TEST FIRST
```

- Red → Green → Refactor — no skipping steps
- Watched the test fail? If not, you don't know if it tests the right thing
- Wrote code before the test? Delete it. Start over.

## TDD Cycle

```
Red      → Write a failing test. Confirm it actually fails.
Green    → Write the minimum implementation to make it pass.
Refactor → Improve code quality while keeping all tests green.
```

## Boundary Analysis (before writing the first test)

Produce a test scenario table before coding:

```
## TDD Boundary Analysis: <FeatureName>

### Unit Under Test
- Class: XxxService
- Method: methodName(ParamType param, ...)

### Test Scenario Table
| # | Scenario            | Test Method Name                                     | Expected Result                        |
|---|---------------------|------------------------------------------------------|----------------------------------------|
| 1 | Valid input         | createArticle_withValidInput_returnsDto              | Returns ArticleDto with non-null id    |
| 2 | Blank title         | createArticle_withBlankTitle_throwsBusinessException | Throws BusinessException, code=400    |
| 3 | Null author         | createArticle_withNullAuthor_throwsBusinessException | Throws BusinessException, code=400    |
| 4 | Duplicate slug      | createArticle_withDuplicateSlug_throwsConflict       | Throws BusinessException, code=409    |
```

## Java Test Naming Convention

`methodName_condition_expectedOutcome`

Examples:
- `createArticle_withNullTitle_throwsBusinessException`
- `login_withWrongPassword_incrementsFailCount`
- `publishArticle_whenDraft_transitionsToPublished`

## Run Commands (this project)

```bash
# Unit tests (*Test.java) — runs by default
./mvnw test -pl <module> -am --no-transfer-progress

# IT tests (*IT.java) — requires surefire <includes> config in module pom.xml
./mvnw test -pl <module> -am --no-transfer-progress
```

Modules: `blog-module-user`, `blog-module-article`, `blog-module-file`, `blog-common`, `blog-infrastructure`

## Verification Checklist

- [ ] Every new method has at least one test
- [ ] Watched each test fail before implementing
- [ ] Wrote minimal code to pass — no extra features
- [ ] All tests pass, output is pristine (no errors/warnings)
- [ ] Edge cases, null inputs, and exception paths covered

## Red Flags — Stop and Restart

- Wrote code before a test
- Test passed immediately without any implementation
- Can't explain why the test failed
- "I'll add tests after" / "Just this once"
- Keeping code as "reference" while writing tests

**All of these mean: Delete code. Start over with TDD.**
