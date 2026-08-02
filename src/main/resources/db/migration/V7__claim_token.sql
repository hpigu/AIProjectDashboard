-- #112 claim token 任務所有權驗證。
-- 只存 token 的安全雜湊，原文只在認領當下回傳一次，不落庫、不進 log。
-- 既有資料列（尚未認領或舊資料）此欄位皆為 NULL，代表沒有 token 保護，
-- 讓部署當下已存在的 IN_PROGRESS / BLOCKED 任務不會被追溯性鎖死。
ALTER TABLE task ADD COLUMN claim_token_hash VARCHAR(128);
