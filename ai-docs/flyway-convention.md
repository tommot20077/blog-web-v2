# Flyway Migration Convention

## Core Rule

**All production migration scripts MUST be placed in `blog-start/src/main/resources/db/migration/` only.**

Other modules MUST NOT place any migration files under `src/main/resources/db/migration/`.

---

## Directory Structure

```
blog-start/
  src/
    main/resources/db/migration/     ← ALL production migrations go here

blog-module-*/
  src/
    test/resources/db/migration/     ← Module IT test migrations only (isolated)
```

---

## Version Numbering

- Use **consecutive integers**: V1, V2, V3, ...
- **No gaps** in version numbers
- **Never modify** a migration that has already been applied to any environment (Flyway checksum validation will fail)

---

## Naming Convention

```
V{N}__{description}.sql
```

- `{N}` = next available integer (check blog-start migrations before choosing)
- `{description}` = English snake_case description
- Double underscore (`__`) between version and description

**Examples:**
- `V8__add_file_metadata.sql`
- `V9__recreate_tags_uuid.sql`
- `V10__add_user_social_links.sql`

---

## Index & Constraint Naming

### 命名規則

| 類型 | Pattern | 例 |
|------|---------|-----|
| Primary Key | `{table}_pkey` | PG 自動產生 |
| Unique Constraint | `uq_{table}_{purpose}` | `uq_article_likes_user_article` |
| Foreign Key | `{table}_{col}_fkey` | PG 自動產生 |
| B-tree Index | `idx_{table}_{purpose}` | `idx_articles_author_id` |
| Partial Index | `idx_{table}_{purpose}` | `idx_comments_article_top_level` |
| GIN/GiST Index | `gin_{table}_{col}` / `gist_{table}_{col}` | — |

### 命名原則

1. 全部小寫 + 底線
2. 上限 < 50 字元（PG 限制 63，留緩衝）
3. 不在欄位名後重複加 `_id`（`idx_articles_author` 優於 `idx_articles_author_id`；但若欄位名就是 `author_id`，保留即可）
4. 複合索引一律用「語意名」而非欄位串接（`idx_comments_article_top_level` 優於 `idx_comments_article_id_created_at`）
5. UNIQUE constraint 在 `ADD CONSTRAINT` 時明確命名，不依賴 PG 自動命名（避免將來 ALTER 時找不到名字）

### 何時建索引

- 預期被查詢的欄位，且該查詢頻率不可忽略
- 關聯外鍵欄位（除非已由 PRIMARY KEY 涵蓋）
- 反正規化計數欄位本身**不**加索引（除非需要 ORDER BY）
- Partial index 適用於大表中只查詢部分行的場景（例：只查頂層留言）

---

## Adding Schema for a New Module

1. Check the current highest version in `blog-start/src/main/resources/db/migration/`
2. Create `V{N+1}__{module_name}_schema.sql` in blog-start
3. Write the DDL matching the module's Model classes exactly
4. Do NOT create any file under the module's `src/main/resources/db/migration/`

---

## Test Migrations

All Flyway migration SQL files (V1..V10 and beyond) are **centralized** in the `blog-db-migration` module (`blog-db-migration/src/main/resources/db/migration/`).

**How modules consume migrations:**

- **Production** (`blog-start`): compile-scope Maven dependency on `blog-db-migration`
- **Module IT tests**: test-scope Maven dependency on `blog-db-migration`

```xml
<!-- In each module's pom.xml, test scope -->
<dependency>
    <artifactId>blog-db-migration</artifactId>
    <scope>test</scope>
</dependency>
```

**Per-module test schemas are ELIMINATED.** No more divergence between test and production schemas. Do NOT create `src/test/resources/db/migration/` directories in individual modules.

**For test-specific seed data**, use Flyway repeatable migrations (`R__*.sql`) placed in `src/test/resources/db/testdata/` and add the location to `application-test.yaml`:

```yaml
spring:
  flyway:
    locations:
      - classpath:db/migration
      - classpath:db/testdata
```

---

## Prohibited Actions

| Prohibited | Reason |
|------------|--------|
| Placing `.sql` files in module `src/main/resources/db/migration/` | Causes Flyway version conflicts with blog-start |
| Editing an already-deployed migration | Flyway checksum mismatch → startup failure |
| Skipping version numbers | Causes confusion about migration history |
| Using different column types between production and test migrations | Schema divergence causes hard-to-debug test failures |
| UNIQUE constraint 靠 PG 自動命名 | 將來 DROP CONSTRAINT 時需查系統表才能找到名字 |
