-- 任務詳情會依 task_id 讀取完整歷史並按 id 排序；複合索引同時支援篩選與順序，
-- 歷史筆數增加時不需額外排序或掃描其他任務的紀錄。
CREATE INDEX idx_task_log_task_id_id ON task_log (task_id, id);
