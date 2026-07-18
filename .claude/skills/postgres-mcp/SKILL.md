---
name: postgres-mcp
description: Query and diagnose the blog_v2_db PostgreSQL database using MCP tools. Use this skill whenever you need to inspect schema, run SQL queries, check slow queries, analyze performance, or debug database issues — even if the user just says "check the DB", "query the database", "what's in the table", or "why is this query slow".
---

# PostgreSQL MCP — blog_v2_db

連線資訊：`postgresql://luca:***@10.0.0.214:30120/blog_v2_db`（credentials 已在 MCP server 環境變數中）

## Quick Start

```
# 1. 先看有哪些 schema
list_schemas()

# 2. 看某個 schema 下的所有表
list_objects(schema="public")

# 3. 看某張表的欄位詳情
get_object_details(schema="public", name="articles")

# 4. 執行查詢
execute_sql(query="SELECT id, title, status FROM articles LIMIT 10")
```

## 工具一覽

### 探索結構

| 工具 | 用途 |
|------|------|
| `list_schemas` | 列出所有 schema |
| `list_objects(schema)` | 列出 schema 下的表、視圖、函式 |
| `get_object_details(schema, name)` | 取得表/視圖欄位定義、約束、索引 |

### 查詢與診斷

| 工具 | 用途 |
|------|------|
| `execute_sql(query)` | **執行任意 SQL**（unrestricted，小心破壞性操作）|
| `explain_query(query)` | 取得執行計劃（EXPLAIN ANALYZE），找全表掃描 |
| `get_top_queries(limit?)` | 取得最慢的 N 筆查詢（需 pg_stat_statements）|
| `analyze_db_health` | 資料庫整體健康報告（連線、快取命中率、鎖等）|
| `analyze_query_indexes(query)` | 針對單一 SQL 建議缺失的索引 |
| `analyze_workload_indexes` | 根據整體工作負載推薦索引 |

## 常用查詢範例

### 查文章狀態分佈
```sql
SELECT status, COUNT(*) FROM articles GROUP BY status;
```

### 查最近發布的文章
```sql
SELECT id, title, slug, published_at
FROM articles
WHERE status = 'PUBLISHED'
ORDER BY published_at DESC
LIMIT 20;
```

### 查 Flyway 遷移歷程
```sql
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;
```

### 查用戶數量與狀態
```sql
SELECT status, COUNT(*) FROM users GROUP BY status;
```

### 查孤立標籤（沒有文章的標籤）
```sql
SELECT t.id, t.name
FROM tags t
LEFT JOIN article_tags at ON t.id = at.tag_id
WHERE at.article_id IS NULL;
```

## 效能分析流程

```
# Step 1: 找慢查詢
get_top_queries(limit=10)

# Step 2: 對特定慢查詢取執行計劃
explain_query(query="SELECT ...")

# Step 3: 取得索引建議
analyze_query_indexes(query="SELECT ...")

# Step 4: 整體健康報告
analyze_db_health()
```

## 注意事項

- `execute_sql` 是 **unrestricted** 模式，可以執行 DELETE/UPDATE/DROP。執行破壞性操作前必須確認。
- 生產資料庫直連，任何寫入操作都會立即生效，沒有事務保護。
- 若要安全探索，優先用 `list_*` / `get_object_details` / `explain_query`。
