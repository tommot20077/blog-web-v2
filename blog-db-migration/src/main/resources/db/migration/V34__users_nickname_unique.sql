-- RACE-02：users.nickname 加唯一約束（Yuan 2026-10-31 同意；跨模組識別 UUID 化計畫的 M8，user 階段）
--
-- 問題：users.nickname 沒有 DB 層唯一約束，註冊（AuthService.register）與改名（UserService.updateProfile）
-- 以 existsByNickname 先查再寫；兩個併發請求都先通過先查，就會寫入重複暱稱（永久的資料錯誤）。
-- 做法：補 uq_users_nickname 作為兜底；應用層保留 existsByNickname 作為快速路徑，寫入時撞到約束
-- 轉成現行的 NICKNAME_DUPLICATED。
--
-- 比對語意與應用層一致：existsByNickname 為精確比對（區分大小寫）且包含軟刪除（DELETED）的使用者，
-- 一般 UNIQUE 約束的語意完全相同，所以不加 lower() 也不加 WHERE status <> 'DELETED'。
--
-- 既有資料有重複時：中止遷移並列出重複的暱稱與筆數，不修改任何資料（需人工處理後再遷移）。
-- 整個 migration 在同一交易內，中止時不留下任何變更。
--
-- 鎖：ALTER TABLE ... ADD CONSTRAINT UNIQUE 會建立索引並短暫持有 ACCESS EXCLUSIVE 鎖；
-- 目前無正式環境、資料量可忽略（設計文件 §4）。

DO $$
DECLARE
    duplicate_nicknames BIGINT;
    duplicate_report    TEXT;
BEGIN
    SELECT COUNT(*) INTO duplicate_nicknames
    FROM (SELECT nickname FROM users GROUP BY nickname HAVING COUNT(*) > 1) d;

    IF duplicate_nicknames > 0 THEN
        -- 報告最多列出 50 組，避免訊息過長；總組數另外標示
        SELECT string_agg(format('"%s" × %s 筆', nickname, cnt), '、' ORDER BY nickname)
        INTO duplicate_report
        FROM (
            SELECT nickname, COUNT(*) AS cnt
            FROM users
            GROUP BY nickname
            HAVING COUNT(*) > 1
            ORDER BY nickname
            LIMIT 50
        ) d;
        RAISE EXCEPTION 'V34 中止：users.nickname 有 % 組重複，請先人工處理（本 migration 不會修改任何資料）後再遷移：%',
            duplicate_nicknames, duplicate_report;
    END IF;
END $$;

ALTER TABLE users ADD CONSTRAINT uq_users_nickname UNIQUE (nickname);
