# Git Commit Convention

## Conventional Commits

*   Follow **Conventional Commits v1.0.0** (https://www.conventionalcommits.org/en/v1.0.0/).
*   Format: `<type>(<scope>): <Traditional Chinese subject>`
*   **type**: `feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore`
*   **scope**: module (`user` / `article` / `common` / `infrastructure`) or layer (`controller` / `service` / `security` …)
*   **Breaking Change**: append `!` after type, add `BREAKING CHANGE: <description>` in footer
*   Subject: Traditional Chinese, imperative mood, no trailing period, max 72 characters
*   Body (optional): motivation and change details after a blank line
*   Footer (optional): `BREAKING CHANGE:`, `Fixes #<issue>`, `Co-Authored-By:`

## 發版與 image（Yuan 2026-11-12）

- **develop → main（release PR）一律用「Create a merge commit」，不可 squash 或 rebase。** squash／rebase 產生的 commit 不是 develop 的祖先，下一次 release PR 會把舊改動重列一次甚至衝突（main 與 develop 曾因此分岔）。也因此不要在 main 開「Require linear history」。
- **hotfix** 合進 main 後，要把 main 合回 develop（同樣用 merge commit），避免兩邊再分岔。
- **image**：CI 的 `image` job 在 PR 時只建置不推（驗證 Dockerfile 與 amd64＋arm64 建置）；main 的 push（＝發版）才推到 GHCR，tag 為 `sha-<短 SHA>` 與 `main`。develop 的 push 不建 image。
- **正式環境的版本**：main 推完 image 後，`infra-pr` job 會在 person-infra 開（或更新）PR，把 `projects/blog/app.prod.env` 的 `BACKEND_TAG` 換成這次的 `sha-…`。合併後在 person-infra 執行 `./scripts/deploy.sh prod`。需要 repo secret `INFRA_REPO_TOKEN`（fine-grained PAT，只授權 person-infra 的 Contents 與 Pull requests 寫入）；沒設定時只留提示，改為手動更新 tag。
- GHCR 套件第一次推送時預設為 private，需在 GitHub 的套件設定改為 public（person-infra positioning §9.2：本機與遠端拉取都不需要憑證）。
