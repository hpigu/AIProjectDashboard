package dev.aiboard.acceptance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Records credential authorization attempts, not claims that a business mutation succeeded. */
@Service
public class ManagementAuditService {
    private final JdbcTemplate db;
    public ManagementAuditService(JdbcTemplate db) { this.db=db; }
    public void record(String tool, boolean authorized) {
        db.update("INSERT INTO management_authorization_audit(actor,tool_name,decision) VALUES(?,?,?)",
                authorized ? "configured-management-credential" : "unverified-caller", tool, authorized ? "AUTHORIZED_ATTEMPT" : "DENIED");
    }
}
