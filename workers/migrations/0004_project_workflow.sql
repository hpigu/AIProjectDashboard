CREATE TABLE project_workflow_revision (
 id INTEGER PRIMARY KEY AUTOINCREMENT,
 project_id INTEGER NOT NULL REFERENCES project(id),
 revision INTEGER NOT NULL,
 state TEXT NOT NULL CHECK(state IN ('DRAFT','CONFIRMED')),
 base_revision INTEGER NOT NULL,
 proposal_json TEXT NOT NULL,
 preview_digest TEXT NOT NULL,
 actor TEXT NOT NULL,
 approval_ref TEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(project_id,revision)
);
