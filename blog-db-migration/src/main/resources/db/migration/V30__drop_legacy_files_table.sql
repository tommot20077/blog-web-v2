-- DROP 舊的 files 表（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4「Migration 授權」、§5 第 5 列，M5）
--
-- 來源與動機：
--   files 由 V1 建立，V8 起已被 file_metadata（UUID 主鍵）取代，之後沒有任何程式碼讀寫它
--   （blog-module-*/src/main 與 blog-infrastructure/src/main 對 files 的讀寫 grep 皆為零）。
--   它的 uploader_id BIGINT NOT NULL REFERENCES users(id) 是殘留的「跨模組 Long 欄位＋FK」，
--   與「模組之間沒有資料庫 FK、跨模組識別一律 UUID」的目標衝突，故整張刪除。
--
-- 授權：Yuan 2026-10-24（設計 §4：「`files` 表整張 DROP」；judgment.md §5）。
--   無正式環境、表內無有效資料；DROP 不可逆，須知此表若有舊資料將一併消失。
--
-- 連帶移除（DROP TABLE 自動處理）：files_pkey、files_uuid_key、files_storage_key_key、
--   idx_files_uploader、idx_files_reference，以及 uploader_id → users(id) 的 FK。
--
-- 範圍邊界：不動 file_metadata（含 uploader_id UUID、article_uuid UUID 無 FK 的設計，見 V20）。

DROP TABLE IF EXISTS files;
