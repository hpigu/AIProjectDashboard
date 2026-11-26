-- Existing completion declarations remain unverified until a new signed contract is accepted.
CREATE TABLE acceptance_event (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 event_id TEXT NOT NULL UNIQUE,
 project_id INTEGER NOT NULL REFERENCES project(id),
 task_id INTEGER NOT NULL REFERENCES task(id),
 actor TEXT NOT NULL,
 kind TEXT NOT NULL,
 payload TEXT NOT NULL,
 created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_acceptance_task ON acceptance_event(task_id,id);
