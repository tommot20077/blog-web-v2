# 正式環境部署檢查清單（遠端機器）

**日期**：2026-06-13
**用途**：正式環境部署在遠端機器、由 Yuan 自理。本檔列出後端 app 容器**必須注入的環境變數**與前端建置設定，供遠端部署時對照。
**網域**：`https://90030.xyz`（前後端同域，外部反代/ingress 負責 `/api`→後端:9010、`/`→前端）。

> 現況提醒：infra repo 只部署 middleware；後端 app **沒有部署清單**（無 k3s Deployment / compose service / CI build-push）。遠端上線前需自建 app 的部署環節（容器化已有 `Dockerfile`，需 image build/push + Deployment/Service + 下列 env 注入）。

---

## 後端 app（blog-start）必要環境變數

| 變數 | 值 / 來源 | 必要性 |
|------|-----------|--------|
| `SPRING_PROFILES_ACTIVE` | `prod` | **必須**。未設會 fallback 到 base 弱預設（`postgres/password`、`minioadmin`、localhost）|
| `DATABASE_URL` | `jdbc:postgresql://<prod-host>:30220/blog_v2_db` | 必須 |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | k3s `infra-secret`（**輪換後的新值**）| 必須 |
| `REDIS_PASSWORD` | infra-secret（輪換後）| 必須 |
| `RABBITMQ_USERNAME` / `RABBITMQ_PASSWORD` | infra-secret（輪換後）| 必須 |
| `ELASTIC_PASSWORD` | infra-secret（輪換後）| 必須 |
| `MINIO_*`（endpoint/access/secret） | infra-secret（輪換後）| 必須 |
| `JWT_PRIVATE_KEY` | 固定 PKCS8 PEM（env 注入）| **必須**。未設則每次重啟動態生成新金鑰 → 所有既有 token 失效 |
| `APP_CORS_ALLOWED_ORIGINS` | `https://90030.xyz` | 建議明設（同域不觸發 CORS，但設為 fail-safe）|
| `APP_FRONTEND_BASE_URL` | `https://90030.xyz` | **必須**（驗證信/重設密碼信的連結用）|

`application-prod.yaml` 上述敏感值皆無預設（啟動失敗保護），務必齊備。

## 前端

- 以 production 模式建置（`.env.production`：`VITE_API_BASE_URL=https://90030.xyz`、`VITE_USE_MOCK=false`，已設定）。
- 產出 `dist/` 由 nginx 提供（`Dockerfile` + `nginx.conf` 已備，含 SPA history fallback、gzip、快取與安全標頭）。
- 外部反代/ingress：`/api`→後端:9010、`/`→前端靜態站。

## 反代 / 限流（建議）

- 後端已有應用層 IP 登入限流（login 20/15min、register 10/hr）。若反代層另有 rate limit 更佳。
- TLS 由外部反代/ingress 提供（前端 nginx 內未設 HSTS/CSP，交由 ingress 統一下發）。

## 上線前最後檢查

1. **憑證輪換**：`tommot40` 這組（DB/Redis/MQ/ES/MinIO 共用、且曾在 git 歷史）必須換新，並重建 `infra-secret`。
2. 確認 app 容器確實帶 `SPRING_PROFILES_ACTIVE=prod` 與上表 env。
3. 後端全測試綠 + 前端 build 綠（見 remediation 計畫上線 gate）。
4. （可選）git 歷史重寫移除舊 dev.yaml（見 remediation 計畫 Task 26）。

## 本地整併驗證

正式部署前可先用本地全端 compose 驗證前後端串接：
```bash
cd D:\end\workspace\infrastructure
docker compose -f docker/docker-compose.fullstack.yml up --build -d
```
（自包式 demo middleware + 後端 demo profile + 前端，詳見 `docker/README-fullstack.md`）
