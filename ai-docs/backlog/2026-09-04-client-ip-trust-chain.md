# Backlog: client IP 信任鏈——XFF 無條件被信任，IP 限流可繞過

- **建立日期**: 2026-09-04
- **來源**: 2026-09-04 三維度稽核 SEC-01（HIGH）＋ ARCH-28；與 Yuan 討論後確認正式拓撲
- **類型**: 安全（限流繞過）＋ 架構（橫切關注點重複實作）
- **關聯**: `ai-docs/findings.md` SEC-01；未合併分支 `origin/claude/fullstack-review-architecture-fdmjbd` 的 `59c170d` 有現成實作

## 問題

develop 有**兩套互相矛盾**的 client IP 解析，且兩套都可被偽造：

| 位置 | 現況 | 問題 |
|------|------|------|
| `AuthController.java:170-176`（登入/註冊限流） | `request.getHeader("X-Forwarded-For").split(",")[0]` | 取**鏈頭**——那是客戶端自己送的值，`curl -H "X-Forwarded-For: 1.2.3.4"` 即可換身分 |
| `ArticleController.java:310-319`（瀏覽數去重） | `request.getRemoteAddr()`，JavaDoc 宣稱「Spring 自動解析 XFF 並更新 RemoteAddr，可避免手動解析被偽造的風險」 | **註解說反了**。`application.yaml:2` 的 `forward-headers-strategy: framework` 沒有信任層數概念，照樣拿 XFF 覆寫 remoteAddr |

後果：登入/註冊的 IP 層級限流、瀏覽數去重整層形同不存在（密碼噴灑、瀏覽數灌水皆無節流）。

## 正式拓撲（2026-09-04 Yuan 確認）

```
prod:   Client → Cloudflare → nginx（反代）→ k3s Ingress → app
local:  Client → app（docker `9010:9010` 直接暴露，前面無 proxy）
```

`docker-compose.fullstack.yml:54-57` 明載「未採 nginx /api proxy 同源拓撲」；前端 vite proxy 只供文章內文相對路徑圖片解析，API 直接打 `VITE_API_BASE_URL`，不經 proxy。

## 建議做法（三層，缺一不可）

### 第 1 層：origin 只接受 Cloudflare 流量（前提，非選配）

若 origin IP 洩漏且無防火牆限制，攻擊者可繞過 CF 直接打 nginx，此時 XFF 完全由其控制，第 2/3 層全部失效。做法擇一：

- k3s 節點防火牆／雲廠商 security group 只放行 [Cloudflare IP 段](https://www.cloudflare.com/ips/)
- 或改用 Cloudflare Tunnel，origin 不開對外 port（更徹底）

### 第 2 層：nginx 用 `realip` 模組收斂，不要讓 XFF 一路長下去

```nginx
set_real_ip_from <Cloudflare IP 段…>;   # 需定期同步 CF 官方清單
real_ip_header CF-Connecting-IP;
real_ip_recursive off;                  # 開啟會沿 XFF 回溯，等於把不可信鏈頭拉回來

location / {
    proxy_set_header X-Forwarded-For $remote_addr;   # 覆寫，不是 $proxy_add_x_forwarded_for
    proxy_pass http://k3s-ingress;
}
```

關鍵在 `$remote_addr`（覆寫成 realip 定案後的真實 IP）而非 `$proxy_add_x_forwarded_for`（追加、保留攻擊者塞的鏈頭）。

### 第 3 層：應用層 `ClientIpResolver`

`origin/claude/fullstack-review-architecture-fdmjbd` 的 `59c170d` 已有現成實作，核心是**從鏈尾往回數**：

```java
public ClientIpResolver(@Value("${app.http.trusted-proxy-count:1}") int trustedProxyCount)

int index = chain.length - trustedProxyCount;
if (index < 0) return request.getRemoteAddr();
return chain[index].trim();
```

攻擊者能往鏈**頭**塞任意值，但塞不進鏈尾——鏈尾各節是自家 proxy 轉發時寫的。倒數第 N 個永遠是真實 IP。

它是 `@Component`，兩個 controller 注入同一個即可消掉「兩份矛盾實作」。

**設定值**：

| 環境 | 拓撲 | `app.http.trusted-proxy-count` |
|------|------|-------------------------------|
| 本地 docker | 無 proxy | `0`（完全不看 XFF） |
| CI e2e | 無 proxy | `0` |
| prod（nginx 已依第 2 層收斂） | Ingress 1 層 | `1` |
| prod（nginx 維持追加、未改） | CF + nginx + Ingress | `3` |

設錯的後果不對稱：設太大 → 取到攻擊者可控節點、等於無防護；設太小 → 取到自家內網 IP、全部人共用一個限流桶（服務退化但不被繞過）。**拿不準時寧可設小。**

### 同批必做

- 移除 `application.yaml:2` 的 `forward-headers-strategy: framework`（改 `none` 或不設）。它無條件拿 XFF 覆寫 remoteAddr，會讓 `ClientIpResolver` 的退路（未污染的 remoteAddr）失效，兩套機制互相打架。
- 刪除 `ArticleController.java:305-313` 那段說反了的 JavaDoc。

## 驗證方式（實測，不要靠推理）

部署後打一次，後端把原始 `X-Forwarded-For` 印進 log：

```bash
curl -H "X-Forwarded-For: 6.6.6.6" https://90030.xyz/api/v1/articles
```

鏈長減 1 即為 `trustedProxyCount`。`6.6.6.6` 必須出現在**鏈頭**，且算出的 index 不得指到它——指到就是設錯。

## 附帶風險（本 backlog 範圍外，但相關）

正式環境的 nginx 反代設定**不在任何 repo**（`infrastructure` repo 只有 `docker/fluent-bit.conf`，`k3s/overlays` 為空）。上述第 2 層設定若只存在單台機器，重建即遺失且無人 review 得到。建議收進 `infrastructure` repo。

## 狀態

**2026-09-04：Yuan 指示暫緩，先記錄。** 應用層（第 3 層 + 同批必做）可由本 repo 單獨完成；第 1、2 層需 `infrastructure` repo 與實際 CF IP 段。

## 現成實作已存進版控（2026-09-04 補充）

原本這份修補只存在未合併的遠端分支 `origin/claude/fullstack-review-architecture-fdmjbd`（commit `59c170d`）。為了讓該分支可以安全刪除，`ClientIpResolver` 的完整實作與測試已收進：

```
ai-docs/backlog/patches/2026-09-04-client-ip-resolver.patch
```

套用方式：

```bash
git apply --3way ai-docs/backlog/patches/2026-09-04-client-ip-resolver.patch
```

**只收了 `ClientIpResolver` 與其測試，沒有收整個 `59c170d`**，理由是 2026-09-04 security review 對該 commit 的四項逐一判定：

| 項目 | develop 現況 |
|------|-------------|
| A1 `changePassword` 撤銷 refresh token | **已涵蓋**，且 develop 以 `SessionRevoker` + `afterCommit` 實作，優於原 commit |
| A2 `resetPassword` 撤銷 refresh token | **已涵蓋**，同上 |
| A3 `/auth/refresh` 驗證 token version | **部分涵蓋**——角色降級缺陷已消失，但權威來源仍是 Redis 快取而非 DB（見 SEC-03 與 triage §4 的 D6） |
| A4 `ClientIpResolver` | **完全未涵蓋** ← 這份 patch |

A1–A3 的原始寫法已被 develop 超越，收進來反而會誤導後人以為需要套用。
