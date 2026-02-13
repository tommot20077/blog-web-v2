# 企業級部落格系統 v2 (Enterprise Blog System v2) - 架構設計與開發指南

**版本**: 1.0.0-SNAPSHOT
**最後更新**: 2026-02-13

---

## 1. 核心設計哲學 (Design Philosophy)

本專案的核心目標是建立一個**可維護 (Maintainable)、可擴展 (Scalable)、且高安全性 (Secure)** 的後端系統。我們拒絕過度工程化 (Over-engineering)，但在關鍵架構決策上保持嚴謹，以應對未來可能的業務增長。

### 1.1 模組化單體 (Modular Monolith)
我們採用「模組化單體」而非直接進入「微服務」，原因如下：
*   **降低複雜度**：避免分散式系統帶來的網路延遲、數據一致性 (Distributed Transaction) 與部署運維成本。
*   **明確邊界 (Bounded Context)**：透過 Maven Multi-Module 強制實體隔離，各模組擁有獨立的 `domain`, `repository`, `service`。
*   **服務門面 (Facade Pattern)**：模組間禁止直接調用 Repository 或 SQL，僅能透過 Service Interface 進行互動。這為未來若需拆分為微服務 (Microservices) 預留了完美的切割點。

### 1.2 領域驅動設計 (DDD) 精神
雖然不完全照搬 DDD 戰術模式，但我們遵循其戰略思想：
*   **充血模型 (Rich Domain Model)**：盡量將業務邏輯封裝在 Entity 中，而非散落在 Service 層。
*   **聚合根 (Aggregate Root)**：各模組負責維護自身數據的一致性 (例如：`User` 模組負責 User 與 Auth 資訊)。

### 1.3 安全優先 (Security First)
安全性不是附加功能，而是基礎建設：
*   **零信任架構**：所有 API 請求 (除公開端點外) 皆需經過嚴格的 JWT 驗證。
*   **ECDSA 簽章**：全面捨棄 RSA，改用 **ES256 (Elliptic Curve)** 進行 JWT 簽章，提供更高的安全性與更小的 Token 體積。
*   **有狀態的無狀態驗證**：JWT 本質是無狀態的，但我們引入 **Redis + Token Version** 機制，實現了「可即時撤銷」的 JWT，解決了傳統 JWT 無法登出或吊銷的痛點。

---

## 2. 系統架構設計 (System Architecture)

### 2.1 邏輯架構圖
```mermaid
graph TD
    Client[Client (Web/Mobile)] --> Gateway[Spring Security Filter Chain]
    
    subgraph "Core Modules"
        Gateway --> UserMod[User Module\n(Auth, Profile)]
        Gateway --> BlogMod[Article Module\n(Content, Comment)]
        Gateway --> FileMod[File Module\n(S3/MinIO)]
        Gateway --> SearchMod[Search Module\n(Elasticsearch)]
    end

    subgraph "Data & Infrastructure"
        UserMod --> DB[(PostgreSQL)]
        UserMod --> Redis[(Redis Cache)]
        
        BlogMod --> DB
        BlogMod --> MQ[RabbitMQ]
        
        SearchMod --> ES[Elasticsearch]
        SearchMod -.-> MQ
        
        FileMod --> MinIO[MinIO Storage]
    end
```

### 2.2 模組劃分與職責
| 模組名稱 | 代號 | 職責範圍 | 關鍵技術 |
|:---------|:-----|:---------|:---------|
| **blog-common** | Common | 存放全域共用的 Enums, Exceptions, Utils, Constants。無任何業務邏輯。 | Jackson, Lang3 |
| **blog-infrastructure** | Infra | 負責與外部系統串接的配置 (Redis, ES, RabbitMQ) 以及全局攔截器 (Security Filter, Global Exception)。 | Spring Security, JJWT |
| **blog-module-user** | User | 處理註冊、登入、JWT 發放、驗證信、密碼重設、個人資料管理。 | MyBatis, Redis Hash |
| **blog-module-article** | Article | 文章的 CRUD、Markdown 解析、留言系統、按讚互動。 | MyBatis, Event Publisher |
| **blog-module-search** | Search | 負責文章與標籤的全文檢索，透過 MQ 訂閱文章變更事件以同步索引。 | Spring Data ES |
| **blog-module-file** | File | 封裝 MinIO 操作，處理圖片/檔案上傳、縮圖生成、權限控管。 | MinIO SDK |

---

## 3. 關鍵技術決策 (Technical Decisions)

### 3.1 為什麼選擇 ECDSA (ES256)？
*   **效能**：橢圓曲線加密在產生簽章與驗證簽章的速度上由優於 RSA。
*   **安全性**：相同安全強度下，ECC Key size 遠小於 RSA (256 bits vs 3072 bits)。
*   **現代化**：符合 NIST 推薦的次世代加密標準。

### 3.2 為什麼使用 Redis Hash 儲存 Token Version？
我們在 Redis 中維護 `user:auth:{userId}` 的 Hash 結構：
```json
{
  "v": "a1b2c3d4",  // Token Version (UUID Substring)
  "s": "ACTIVE"     // User Status
}
```
*   **空間效率**：Hash 結構比多個 String Key 更節省記憶體。
*   **原子性**：可以透過 `HGETALL` 一次取回版本號與狀態，減少網路 RTT。
*   **即時性**：當用戶修改密碼或被管理員封鎖時，只需修改 Redis 中的值，該用戶持有的所有舊 JWT 立即失效。

### 3.3 為什麼引入 RabbitMQ？
為了**解耦 (Decoupling)** 與 **流量削峰 (Throttling)**：
*   **寫入解耦**：文章發布後，不應同步等待 Elasticsearch 索引建立或發送訂閱通知。透過 MQ 發送 `ArticlePublishedEvent`，讓 Search Module 自行消費處理。
*   **可靠性**：即使 Elasticsearch 暫時掛掉，訊息仍保留在 Queue 中，待服務恢復後可繼續同步，保證數據最終一致性。

### 3.4 基礎設施選型：K3s & MinIO
*   **K3s**：輕量級 Kubernetes，適合邊緣計算或中小規模集群，但完全相容 K8s API，方便未來遷移至 EKS/GKE。
*   **MinIO**：自建 S3 相容存儲，避免在開發階段就被特定雲端供應商 (Vendor Lock-in) 綁定，且部署簡單高效。

---

## 4. 資料庫設計規範 (Database Schema Standards)

*   **UUID Primary Key**：雖然內部使用 `BIGINT` 作為 PK 以優化索引效能，但對外 API **一律使用 UUID**，避免 ID 列舉攻擊 (Enumeration Attack)。
*   **Audit Fields**：所有業務表必須包含 `created_at` 與 `updated_at`，並透過 MyBatis Interceptor 自動維護。
*   **Soft Delete**：不建議使用軟刪除 (Soft Delete)，因為會增加索引複雜度與 Unique Key 判斷難度。建議將「封存/刪除」狀態顯式設計在 `status` 欄位中，或將歷史數據搬移至 History 表。

---

## 5. 開發規範 (Development Guidelines)

### 5.1 API 回應格式
所有 API (無論成功或失敗) 統一回傳 `ApiResponse<T>`：
```json
{
  "code": 200,
  "message": "success",
  "data": { ... },
  "timestamp": 1700000000000
}
```
*   禁止 Controller 直接回傳 Entity，必須轉換為 **DTO (Data Transfer Object)**。

### 5.2 異常處理
*   **BusinessException**：業務邏輯錯誤 (如：餘額不足、權限不符)，需搭配 `IErrorCode` Enum。
*   **GlobalExceptionHandler**：在 `Infrastructure` 層統一攔截所有異常，轉換為標準 API 錯誤回應，避免將 Stack Trace 暴露給前端。

