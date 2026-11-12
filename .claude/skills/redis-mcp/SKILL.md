---
name: redis-mcp
description: Inspect and manipulate Redis cache data for the blog-web-v2 project using MCP tools. Use this skill whenever you need to check cache state, inspect Redis keys, debug caching issues, check rate limits, view session data, or manage any Redis data — even if the user says "check Redis", "look at the cache", "is this user locked out", or "what's in the rate limit key".
---

# Redis MCP — blog-web-v2

連線：`redis://10.0.0.214:30121/0`

## 專案 Key 命名規範

這個專案所有 key 都定義在 `RedisKeyConstant.java`：

| 分類 | Key Pattern | 資料結構 | 說明 |
|------|------------|----------|------|
| 用戶認證 | `user:auth:{userId}` | Hash | fields: version, status, role |
| Refresh Token | `user:refresh:{userId}` | String | JWT refresh token |
| 登入失敗計數 | `login:fail:{userId}` | String | 超過 5 次鎖定 15 分鐘 |
| 忘記密碼限速/分 | `rate:forgot-pwd:min:{email}` | String | 每分鐘次數 |
| 忘記密碼限速/日 | `rate:forgot-pwd:day:{email}` | String | 每日次數 |
| 重發驗證信/分 | `rate:resend-verify:min:{email}` | String | 每分鐘次數 |
| 重發驗證信/日 | `rate:resend-verify:day:{email}` | String | 每日次數 |
| 文章瀏覽增量 | `article:views:{articleUuid}` | String | 定時 flush 到 DB |
| 熱門標籤 | `tag:hot` | ZSet | score = 文章數量 |
| 標籤自動補全 | `tag:autocomplete` | ZSet | lexicographic |
| 標籤詳情 | `tag:{slug}` | Hash | 標籤 metadata |
| 熱門搜尋詞 | `search:hot` | ZSet | score = 搜尋次數 |
| 搜尋歷史 | `search:history:{userId}` | List | 個人搜尋紀錄（TTL 30天）|
| 熱門排行 | `recommend:trending:{period}` | ZSet | period: 24h/7d/30d |
| 相關文章快取 | `recommend:related:{articleUuid}` | String/JSON | |
| 分散式鎖 | `lock:trending-refresh` | String | trending 更新鎖 |

## Quick Start

```
# 查看所有 key（謹慎，生產環境）
scan_keys(pattern="*", count=20)

# 查特定前綴
scan_keys(pattern="user:auth:*")

# 取得 key 類型
type(key="tag:hot")

# 查看用戶認證快取
hgetall(key="user:auth:123")

# 查看熱門標籤排行（前 10）
zrange(key="tag:hot", start=0, end=9, rev=true, withscores=true)
```

## 工具分類

### 探索
- `scan_keys(pattern, count?)` — 掃描符合 pattern 的 key
- `scan_all_keys(pattern?)` — 掃描所有（小心大量資料）
- `type(key)` — 取得 key 的資料結構類型
- `dbsize()` — 取得 key 總數
- `info(section?)` — Redis server 資訊（memory, stats, replication）
- `client_list()` — 列出所有連線客戶端

### String 操作
- `get(key)` — 讀取
- `set(key, value, ex?)` — 寫入，ex 為 TTL（秒）
- `delete(key)` — 刪除
- `expire(key, seconds)` — 設定 TTL
- `rename(key, newkey)` — 改名

### Hash 操作（`user:auth:*`, `tag:{slug}`）
- `hgetall(key)` — 讀取全部 fields
- `hget(key, field)` — 讀取單一 field
- `hset(key, field, value)` — 寫入 field
- `hdel(key, field)` — 刪除 field
- `hexists(key, field)` — 確認 field 是否存在

### List 操作（`search:history:*`）
- `lrange(key, start, stop)` — 讀取範圍（0, -1 = 全部）
- `llen(key)` — 長度
- `lpush(key, value)` — 左端插入
- `rpush(key, value)` — 右端插入
- `lpop(key)` — 左端取出
- `rpop(key)` — 右端取出
- `lrem(key, count, value)` — 移除元素

### Sorted Set 操作（`tag:hot`, `search:hot`, `recommend:trending:*`）
- `zrange(key, start, end, rev?, withscores?)` — 讀取範圍
- `zadd(key, score, member)` — 新增/更新
- `zrem(key, member)` — 移除
- `zrange(key, 0, 9, rev=true, withscores=true)` — 取 Top 10

### Set 操作
- `smembers(key)` — 取全部成員
- `sadd(key, member)` — 新增
- `srem(key, member)` — 移除

### Stream 操作（RabbitMQ 事件 log 若有用 Redis Stream）
- `xadd(key, id, fields)` — 寫入
- `xrange(key, start, end)` — 讀取範圍
- `xdel(key, id)` — 刪除

### JSON 操作
- `json_get(key, path?)` — 讀取 JSON（path 預設 `$`）
- `json_set(key, path, value)` — 寫入
- `json_del(key, path?)` — 刪除

### Pub/Sub
- `publish(channel, message)` — 發布訊息
- `subscribe(channel)` — 訂閱（debug 用）
- `unsubscribe(channel)` — 取消訂閱

## 常用診斷情境

### 確認用戶是否被鎖定
```
get(key="login:fail:{userId}")
# 值 >= 5 表示帳號鎖定中
```

### 清除用戶認證快取（強制重新登入）
```
delete(key="user:auth:{userId}")
delete(key="user:refresh:{userId}")
```

### 查看熱門標籤
```
zrange(key="tag:hot", start=0, end=9, rev=true, withscores=true)
```

### 查看某用戶搜尋歷史
```
lrange(key="search:history:{userId}", start=0, stop=-1)
```

### 查看記憶體使用
```
info(section="memory")
```

### 手動釋放分散式鎖（若程序崩潰未釋放）
```
delete(key="lock:trending-refresh")
```
