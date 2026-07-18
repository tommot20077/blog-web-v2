---
description: Guide for blog-web-v2 Java Spring Boot modular monolith architecture. Use this skill when designing new features, reviewing code structure, or making architectural decisions.
---

# blog-web-v2 Architecture Guide

## 1. Module Boundaries

- Every feature must belong to one module: `user` / `article` / `file` / `tag` / `search` / `recommend`
- **Cross-module calls**: Only through Facade interfaces defined in `blog-infrastructure`. Direct imports of another module's Repository or Service impl are forbidden.
- `blog-common` contains only shared DTOs, Enums, ErrorCodes, and Exceptions. **No Spring Beans allowed.**

## 2. Layer Responsibilities

| Layer | Responsibility |
|-------|---------------|
| Controller | Validate input, call Service, return `ApiResponse<T>` |
| Service | Business logic, state machines, event publishing |
| Repository | JPA/MyBatis queries only — no business decisions |
| Entity | Rich Domain Model — encapsulates invariants and state transitions |
| Facade | Cross-module interface (interface in `blog-infrastructure`, impl in each module) |

- Controller **must not contain business logic**
- Repository **must not make business decisions**
- Entity methods are responsible for **guarding state transitions** (e.g., ArticleStatus state machine)
- Service **must not return JPA Entities to Controller** — convert to DTO/Response first

## 3. Naming Conventions

| Layer | Example Names |
|-------|--------------|
| Controller | `ArticleController`, `AdminArticleController` |
| Service interface | `ArticleService`, `AuthService` |
| Service impl | `ArticleServiceImpl`, `AuthServiceImpl` |
| Repository | `ArticleRepository`, `UserRepository` |
| Entity | `Article`, `User`, `ArticleContent` |
| Facade interface | `UserFacade` (placed in `blog-infrastructure`) |
| Facade impl | `UserFacadeImpl` (placed in each module) |
| Event | `UserRegisteredEvent`, `ArticlePublishedEvent` |
| ErrorCode | `ArticleErrorCode`, `UserErrorCode` (implements `IErrorCode`) |

- Forbidden suffixes: `Utils`, `Helper`, `Manager`, `Common` (except the `blog-common` module itself)
- Prefer domain-specific names: `ArticleStateTransitionException` over `InvalidStateException`

## 4. API Design

- All responses must be wrapped in `ApiResponse<T>` (from `blog-common`)
- External IDs must always be **UUID**; internal PKs remain Long
- URL paths use UUID: `/api/articles/{uuid}`, not `/api/articles/{id}`
- Admin endpoints: `/api/admin/**` — must be configured with `hasRole("ADMIN")` **before** `anyRequest()` in `SecurityConfig`

## 5. Security Principles

- JWT signing: **ECDSA ES256** only — RSA and HMAC are forbidden
- Stateful JWT: every validation must check the token version stored in Redis at `user:auth:{id}`
- Passwords: BCrypt via `PasswordEncoder`
- RBAC: dual-layer `Role` + `Permission`; use `@PreAuthorize` for method-level authorization

## 6. Infrastructure Usage

| Technology | Use For | Never Use For |
|------------|---------|---------------|
| Redis | Token version, Session, short-lived cache | Persistent business data |
| RabbitMQ | Async events (email, search index updates) | Synchronous request-response |
| Elasticsearch | Full-text search, complex filtering | Replacing primary database queries |
| MinIO | User-uploaded binary files | Text data |
| PostgreSQL | Primary business data | Full-text search (use ES instead) |

## 7. Anti-Patterns

- Cross-module `@Autowired` of another module's Repository
- Controller directly accessing Repository
- Injecting Spring Beans (`@Autowired`) inside an Entity
- Using RSA or HMAC for JWT signing
- Swallowing `BusinessException` without re-throwing
- Returning JPA Entities from Service to Controller (use DTO/Response instead)
- Placing shared logic in `XXXUtils` static classes

## 8. Code Quality

- **TDD is mandatory**: write a failing test first (Red), then minimal implementation (Green), then refactor
- **JavaDoc required (Traditional Chinese)**: all public classes, methods, and member variables
- **No single-line `//` comments**: use `/** ... */` block style only
- Target function length ≤ 50 lines; target file length ≤ 200 lines
- Prefer early return over nested conditions
- Maximum nesting depth: 3 levels
