-- V23__normalize_category_slugs.sql
-- 目的：分類 slug 限定為「小寫英數字，以單一連字號分隔」（Yuan 2026-09-27 決定，選項 C）。
--   原本 API 只驗 @NotBlank / @Size，管理員可存入大寫、逗號、空白、非 ASCII：
--   逗號是文章列表多值參數 categorySlug 的分隔符（含逗號的分類永遠篩不到），
--   大寫則被前端轉小寫後的請求錯過。
--
-- 步驟一：正規化既有的不合格 slug。
--   規則：轉小寫 → 非 [a-z0-9] 的連續字元換成單一 '-' → 去頭尾 '-'；
--         結果為空（如純中文）改用 category-{id}；
--         與其他分類撞名時附加 -{id}（仍撞則 -{id}-2、-{id}-3…），必要時截短 base 使總長 ≤ 60。
--   逐筆依 id 處理：較早建立的分類優先取得正規化後的名稱。
--   ⚠️ 會改變這些分類的網址，且刻意不留轉址（Yuan 決定）；每筆改寫以 RAISE NOTICE 記錄於 migration log。
--   合格的 slug 完全不動；不存在不合格資料時本步驟為空操作。
--
-- 步驟二：以 CHECK 約束保證之後任何寫入路徑（API 以外的 SQL 亦然）都無法再存入不合格 slug。
--   regex 必須與 Category.SLUG_PATTERN（API 層 @Pattern）完全一致。

DO $$
DECLARE
    rec       RECORD;
    base      TEXT;
    candidate TEXT;
    suffix    TEXT;
    attempt   INT;
BEGIN
    FOR rec IN
        SELECT id, slug FROM categories
        WHERE slug !~ '^[a-z0-9]+(-[a-z0-9]+)*$'
        ORDER BY id
    LOOP
        base := trim(BOTH '-' FROM regexp_replace(lower(rec.slug), '[^a-z0-9]+', '-', 'g'));
        IF base = '' THEN
            base := 'category-' || rec.id;
        END IF;
        candidate := rtrim(left(base, 60), '-');
        attempt := 0;
        WHILE EXISTS (SELECT 1 FROM categories WHERE slug = candidate AND id <> rec.id) LOOP
            attempt := attempt + 1;
            suffix := '-' || rec.id || CASE WHEN attempt > 1 THEN '-' || attempt ELSE '' END;
            candidate := rtrim(left(base, 60 - length(suffix)), '-') || suffix;
        END LOOP;
        RAISE NOTICE 'V23 category % slug: "%" -> "%"', rec.id, rec.slug, candidate;
        UPDATE categories SET slug = candidate WHERE id = rec.id;
    END LOOP;
END $$;

ALTER TABLE categories
    ADD CONSTRAINT ck_categories_slug_format CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');
