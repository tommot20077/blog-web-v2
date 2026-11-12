---
name: elasticsearch-mcp
description: Search and inspect Elasticsearch indices for the blog-web-v2 project using MCP tools. Use this skill whenever you need to search articles, check ES index mappings, debug full-text search, inspect index health, or run ES|QL queries — even if the user says "search in ES", "check the search index", "why isn't this article showing up", or "run an Elasticsearch query".
---

# Elasticsearch MCP — blog-web-v2

連線：`http://10.0.0.214:30124/`（API key 已在 MCP server 環境變數中）

## 專案索引結構

### 索引名稱：`blog_articles`

只索引 `status = PUBLISHED` 的文章。

| 欄位 | ES 型別 | 說明 |
|------|---------|------|
| `id` | keyword | 文章 UUID 字串（document ID）|
| `title` | text | 全文檢索，IK Analyzer，boost 3.0 |
| `summary` | text | 全文檢索，boost 2.0 |
| `content` | text | 全文檢索（Markdown 去格式後），boost 1.0 |
| `slug` | keyword | URL slug |
| `author.id` | keyword | 作者 DB 主鍵（Long）|
| `author.username` | keyword | 作者帳號 |
| `author.nickname` | text | 作者暱稱 |
| `tags` | nested | 標籤列表 |
| `tags.id` | keyword | 標籤 UUID |
| `tags.name` | keyword | 標籤名稱 |
| `tags.slug` | keyword | 標籤 slug |
| `publishedAt` | date | 格式：`date_hour_minute_second` |
| `viewCount` | integer | 瀏覽次數（Function Score 排序用）|
| `likeCount` | integer | 按讚次數 |
| `status` | keyword | 只有 `PUBLISHED` |

## 工具一覽

| 工具 | 用途 |
|------|------|
| `list_indices` | 列出所有索引及其狀態（document 數、大小、健康）|
| `get_mappings(index)` | 取得索引 mapping（欄位定義）|
| `get_shards(index?)` | 取得 shard 分配狀態 |
| `search(index, query)` | 執行 Elasticsearch Query DSL 搜尋 |
| `esql(query)` | 執行 ES|QL 查詢（類 SQL 語法）|

## Quick Start

```
# 列出所有索引
list_indices()

# 查看 blog_articles 的 mapping
get_mappings(index="blog_articles")

# 全文搜尋
search(
  index="blog_articles",
  query={
    "query": {
      "multi_match": {
        "query": "Spring Boot",
        "fields": ["title^3", "summary^2", "content"]
      }
    },
    "size": 5
  }
)
```

## 常用搜尋範例

### 全文搜尋（multi_match）
```json
{
  "query": {
    "multi_match": {
      "query": "搜尋關鍵字",
      "fields": ["title^3", "summary^2", "content"]
    }
  },
  "size": 10
}
```

### 用 keyword 精確過濾（status, slug, author.username）
```json
{
  "query": {
    "term": { "status": "PUBLISHED" }
  }
}
```

### 依 tag 過濾（Nested query）
```json
{
  "query": {
    "nested": {
      "path": "tags",
      "query": {
        "term": { "tags.slug": "java" }
      }
    }
  }
}
```

### 依作者查詢
```json
{
  "query": {
    "term": { "author.username": "yuan" }
  },
  "sort": [{ "publishedAt": "desc" }]
}
```

### Function Score（按熱度排序）
```json
{
  "query": {
    "function_score": {
      "query": { "match_all": {} },
      "functions": [
        { "field_value_factor": { "field": "viewCount", "factor": 0.5 } },
        { "field_value_factor": { "field": "likeCount", "factor": 1.0 } }
      ]
    }
  }
}
```

### 確認特定文章是否在索引中
```json
{
  "query": {
    "term": { "_id": "article-uuid-here" }
  }
}
```

## ES|QL 範例

```
# 統計各作者的文章數量
FROM blog_articles
| STATS count = COUNT() BY author.username
| SORT count DESC

# 查看最近發布的文章
FROM blog_articles
| SORT publishedAt DESC
| LIMIT 10
| KEEP title, slug, publishedAt, viewCount
```

## 常用診斷情境

### 文章消失排查流程
```
# Step 1: 確認索引存在且有資料
list_indices()

# Step 2: 直接查 document ID（文章 UUID）
search(index="blog_articles", query={"query": {"term": {"_id": "uuid"}}})

# Step 3: 若不存在，可能是 status != PUBLISHED 或同步問題
# 回頭查 PostgreSQL 的文章 status
```

### 索引健康狀態
```
list_indices()      # 看 health (green/yellow/red) 和 doc 數量
get_shards(index="blog_articles")   # 看 shard 分配
```
