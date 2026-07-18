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
