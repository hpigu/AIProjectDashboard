package dev.aiboard.acceptance;

import dev.aiboard.common.BoardException;
import dev.aiboard.task.TaskRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Append-only, scoped signed attestations. No commands, URLs or artifacts are executed/fetched. */
@Service
public class AcceptanceService {
    private final JdbcTemplate db;
    private final TaskRepository tasks;
    private final dev.aiboard.project.ProjectService projects;
    private final AcceptanceAuthority authority;
    private dev.aiboard.workflow.ProjectWorkflowService workflows;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureWorkflows(dev.aiboard.workflow.ProjectWorkflowService workflows) { this.workflows = workflows; }
    private final JsonMapper json = JsonMapper.builder().build();
    public AcceptanceService(JdbcTemplate db, TaskRepository tasks, dev.aiboard.project.ProjectService projects,
            @Value("${BOARD_ACCEPTANCE_AUTHORITIES:}") String config) {
        this.db = db; this.tasks = tasks; this.projects=projects;
        this.authority = new AcceptanceAuthority(config);
    }
    private void require(boolean ok, String message) { if (!ok) throw new BoardException(message); }
    private String text(JsonNode n, String key) {
        String s = n.path(key).asText("");
        require(!s.isBlank(), "驗收欄位不可空白：" + key); return s;
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String taskDigest(long taskId) {
        var t = tasks.findById(taskId).orElseThrow(() -> new BoardException("找不到任務"));
        return digest(json.writeValueAsString(List.of(t.getTitle(), Objects.toString(t.getDescription(), ""), t.getCategory())));
    }
    @Transactional
    public Summary record(String actor, String payload, String signature) {
        JsonNode key = authority.verifySignature(actor, payload, signature);
        JsonNode event = json.readTree(payload);
        long taskId = event.path("taskId").asLong();
        var task = tasks.findById(taskId).orElseThrow(() -> new BoardException("找不到任務"));
        projects.assertActiveForUpdate(task.getProjectId());
        // Lock the same task row used by optimistic lifecycle writes; all evidence changes bump its version.
        require(event.path("projectId").asLong() == task.getProjectId(), "驗收專案不符");
        String kind = text(event, "kind");
        require(Set.of("CONTRACT", "RUN", "REVIEW", "EXCEPTION", "INTEGRATION", "DISPATCH", "RELEASE", "PROJECT_POLICY").contains(kind), "未知驗收事件");
        require(contains(key.path("projectIds"), task.getProjectId().toString()) && contains(key.path("permissions"), kind), "驗收身分沒有此專案／操作權限");
        var rows = rows(taskId);
        require(event.path("expectedRevision").asInt(-1) == rows.size(), "驗收紀錄已更新，請重新讀取");
        JsonNode body = event.path("body");
        if (kind.equals("PROJECT_POLICY")) {
            require(workflows == null || !workflows.hasRevisions(task.getProjectId()), "專案已使用流程規劃，請透過草稿與獨立核准更新");
            validateGit(body.path("gitPolicy"));
        }
        else if (kind.equals("CONTRACT")) {
            validateContract(body, taskId);
            require(task.getAssignee() == null || body.path("implementationActor").asText().equals(task.getAssignee()), "契約原作者必須是目前認領者");
            JsonNode policy = projectPolicy(task.getProjectId());
            require(policy != null && policy.path("body").path("gitPolicy").equals(body.path("gitPolicy")), "尚無符合的專案 Git 設定");
            require(workflowVersionMatches(task.getProjectId(), body), "專案流程版本不符，請採用目前已確認版本");
        }
        else if (!kind.equals("DISPATCH")) {
            var contract = latest(rows, "CONTRACT");
            require(contract != null && text(body, "contractId").equals(contract.path("eventId").asText()), "驗收契約版本不符");
            require(text(body, "codeRef").equals(contract.path("body").path("codeRef").asText()), "受測程式版本不符");
            if (kind.equals("RUN")) {
                require(!actor.equals(contract.path("body").path("implementationActor").asText()), "開發代理聲明不屬受控執行器證據");
                require(contains(key.path("runnerProfiles"), text(body, "runnerProfile")), "未核准的執行器");
                for (String field : List.of("caseId", "runId", "result", "artifactUri", "artifactSha256", "steps", "actual", "environment")) text(body, field);
                require(body.path("artifactSha256").asText().matches("[a-f0-9]{64}"), "證據必須有 SHA-256");
                require(Set.of("PASSED", "FAILED", "NOT_RUN").contains(body.path("result").asText()), "未知執行結果");
                require(body.path("environment").asText().equals("ISOLATED"), "僅接受隔離測試環境");
                require(findCase(contract.path("body"), body.path("caseId").asText()) != null, "測試案例不在契約內");
            } else if (kind.equals("REVIEW")) {
                require(!actor.equals(task.getAssignee()) && !actor.equals(contract.path("body").path("implementationActor").asText()), "原作者不能是唯一驗收者");
                for (JsonNode row : rows) if (row.path("kind").asText().equals("RUN") && row.path("body").path("contractId").asText().equals(contract.path("eventId").asText())) require(!actor.equals(row.path("_verifiedActor").asText()), "執行者不能獨自核准驗收");
                require(text(body, "subjectDigest").equals(subject(rows)), "審查未涵蓋目前證據");
                text(body, "reason"); require(Set.of("APPROVED", "REJECTED").contains(text(body, "decision")), "未知審查結論");
            } else if (kind.equals("EXCEPTION")) { require(!actor.equals(task.getAssignee()) && !actor.equals(contract.path("body").path("implementationActor").asText()), "開發代理不得自行核准例外"); text(body, "caseId"); text(body, "reason"); text(body, "approvalRef"); require(findCase(contract.path("body"),body.path("caseId").asText()) != null, "例外案例不存在"); }
            else { text(body, "ref"); }
        } else {
            for (String field : List.of("sessionId", "worktree", "branch", "baseRef", "lastSeen", "handoff")) text(body, field);
        }
        String eventId = text(event, "eventId");
        require(eventId.length() <= 100 && db.queryForObject("SELECT COUNT(*) FROM acceptance_event WHERE event_id=?", Long.class, eventId) == 0, "重複驗收事件");
        // CAS against task version serializes acceptance imports and completion on the same database row.
        require(db.update("UPDATE task SET version=version+1 WHERE id=? AND version=?", taskId, task.getVersion()) == 1, "任務已更新，請重試");
        db.update("INSERT INTO acceptance_event(event_id,project_id,task_id,actor,kind,payload) VALUES(?,?,?,?,?,?)",
                eventId, task.getProjectId(), taskId, actor, kind, payload);
        return summary(taskId);
    }
    private boolean immutableCodeRef(String value) {
        return value != null && value.matches("(?:[a-f0-9]{40}|[a-f0-9]{64}|sha256:[a-f0-9]{64})");
    }
    private boolean contains(JsonNode items, String value) { for (JsonNode item : items) if (item.asText().equals(value)) return true; return false; }
    private void validateContract(JsonNode b, long taskId) {
        for (String f : List.of("sourceRef", "sourceVersion", "codeRef", "taskDigest", "flowRef", "flowVersion", "implementationActor")) text(b, f);
        require(b.path("codeRef").isTextual() && immutableCodeRef(text(b, "codeRef")), "受測版本必須是完整 Git SHA 或 sha256 digest，不能使用可變分支／標籤");
        require(text(b, "taskDigest").equals(taskDigest(taskId)), "需求描述已改變，請重新確認契約");
        require(b.path("requirements").isArray() && !b.path("requirements").isEmpty(), "契約必須有需求");
        require(b.path("questions").isArray() && b.path("assumptions").isArray(), "問題與推測須明確列出（可為空）");
        var ids = new HashSet<String>();
        for (JsonNode r : b.path("requirements")) { require(ids.add(text(r, "id")), "需求 ID 重複"); text(r,"acceptance"); require(Set.of("CONFIRMED","PENDING","ASSUMPTION").contains(text(r,"state")), "需求狀態不合法"); }
        require(b.path("cases").isArray() && !b.path("cases").isEmpty(), "契約必須有測試案例");
        var cases = new HashSet<String>();
        for (JsonNode c : b.path("cases")) {
            require(cases.add(text(c,"id")), "案例 ID 重複"); text(c,"kind"); text(c,"scenario");
            require(c.path("requirementIds").isArray() && !c.path("requirementIds").isEmpty(), "案例須連結需求");
            for (JsonNode id : c.path("requirementIds")) require(ids.contains(id.asText()), "案例參照不存在的需求");
        }
        if (b.path("browserApplicable").isBoolean() && !b.path("browserApplicable").asBoolean()) text(b, "browserExemptionReason");
        validateGit(b.path("gitPolicy"));
    }
    private void validateGit(JsonNode git) {
        for (String f : List.of("baseBranch", "integrationTarget", "reviewPolicy", "mergeAuthority", "pullRequestPolicy", "requiredChecks")) text(git,f);
        require(Set.of("ACCEPTED", "INTEGRATED").contains(text(git,"doneWhen")), "Git 流程須明訂 DONE 時機");
    }
    private JsonNode findCase(JsonNode b, String id) { for (JsonNode c : b.path("cases")) if (c.path("id").asText().equals(id)) return c; return null; }
    private List<JsonNode> rows(long taskId) { return db.query("SELECT payload,actor,created_at FROM acceptance_event WHERE task_id=? ORDER BY id", (rs,i) -> {
        var node=(tools.jackson.databind.node.ObjectNode)json.readTree(rs.getString(1)); node.put("_verifiedActor",rs.getString(2)); node.put("_receivedAt",rs.getString(3)); return (JsonNode)node;
    }, taskId); }
    private JsonNode projectPolicy(long projectId) {
        if (workflows != null) {
            var effective = workflows.effective(projectId);
            if (effective != null && effective.version() > 0)
                return json.valueToTree(Map.of("body", Map.of("gitPolicy", effective.gitPolicy())));
        }
        var values=db.query("SELECT payload FROM acceptance_event WHERE project_id=? AND kind='PROJECT_POLICY' ORDER BY id DESC FETCH FIRST 1 ROW ONLY", (rs,i)->json.readTree(rs.getString(1)),projectId);
        return values.isEmpty()?null:values.getFirst();
    }
    private boolean workflowVersionMatches(long projectId, JsonNode body) {
        if (workflows == null) return true;
        var policy = workflows.effective(projectId);
        return policy == null || policy.version() == 0 || (body.path("workflowVersion").isIntegralNumber() && body.path("workflowVersion").asLong() == policy.version());
    }
    private JsonNode latest(List<JsonNode> rows, String kind) { JsonNode found = null; for (JsonNode e: rows) if(e.path("kind").asText().equals(kind)) found=e; return found; }
    private String subject(List<JsonNode> rows) { return digest(rows.stream().filter(e -> Set.of("CONTRACT","RUN","EXCEPTION").contains(e.path("kind").asText())).map(e -> e.path("eventId").asText()).reduce("", (a,b)->a+b+"\n")); }
    @Transactional(readOnly=true)
    public Summary summary(long taskId) {
        var rows = rows(taskId); var contract = latest(rows,"CONTRACT"); var blockers = new ArrayList<String>(); var passed = new ArrayList<String>();
        if (contract == null) blockers.add("尚無已核准驗收契約；舊紀錄與代理聲明未驗證");
        else {
            JsonNode b = contract.path("body"); String cid=contract.path("eventId").asText();
            if (!immutableCodeRef(b.path("codeRef").asText())) blockers.add("受測版本不是不可變識別，請重新確認契約");
            if (!b.path("taskDigest").asText().equals(taskDigest(taskId))) blockers.add("任務需求已變更，契約失效");
            var task = tasks.findById(taskId).orElseThrow();
            JsonNode policy=projectPolicy(task.getProjectId());
            if(policy==null || !policy.path("body").path("gitPolicy").equals(b.path("gitPolicy"))) blockers.add("專案 Git 流程已變更，請重新確認契約");
            if(!workflowVersionMatches(task.getProjectId(), b)) blockers.add("專案流程版本已變更，請重新確認契約適用性");
            if(task.getAssignee()!=null && !task.getAssignee().equals(b.path("implementationActor").asText())) blockers.add("認領者與契約原作者不符，請重新確認");
            if (!b.path("questions").isEmpty()) blockers.add("有待人類決定的需求問題");
            for (JsonNode r:b.path("requirements")) {
                if (!r.path("state").asText().equals("CONFIRMED")) blockers.add("需求未確認："+r.path("id").asText());
                boolean covered=false;
                for (JsonNode c:b.path("cases")) if (contains(c.path("requirementIds"),r.path("id").asText())) covered=true;
                if(!covered) blockers.add("需求漏驗："+r.path("id").asText());
            }
            boolean browser=false;
            for(JsonNode c:b.path("cases")) {
                if(c.path("kind").asText().equals("BROWSER")) browser=true;
                JsonNode run=null, exception=null;
                for(JsonNode e:rows) if(e.path("body").path("contractId").asText().equals(cid) && e.path("body").path("caseId").asText().equals(c.path("id").asText())) {
                    if(e.path("kind").asText().equals("RUN")) { run=e; exception=null; }
                    if(e.path("kind").asText().equals("EXCEPTION")) exception=e;
                }
                if(run!=null && run.path("body").path("result").asText().equals("PASSED")) passed.add(c.path("id").asText());
                else if(exception==null) blockers.add("失敗或未驗證："+c.path("id").asText());
            }
            if(!browser && !(b.path("browserApplicable").isBoolean() && !b.path("browserApplicable").asBoolean() && !b.path("browserExemptionReason").asText("").isBlank())) blockers.add("缺少真實瀏覽器流程案例（非 UI 專案須由需求負責人明訂適用性）");
            boolean browserApplicable=!(b.path("browserApplicable").isBoolean() && !b.path("browserApplicable").asBoolean());
            if(browserApplicable) for(String scenario:List.of("NORMAL","ERROR","BOUNDARY","PERMISSION","FAILURE","PERSISTENCE","DOWNSTREAM")) {
                boolean covered=false; for(JsonNode c:b.path("cases")) if(c.path("scenario").asText().equals(scenario)) covered=true;
                if(!covered && b.path("scenarioExemptions").path(scenario).asText("").isBlank()) blockers.add("缺少適用情境或需求負責人核准免驗理由："+scenario);
            }
            var review=latest(rows,"REVIEW");
            if(review==null || !review.path("body").path("contractId").asText().equals(cid) || !review.path("body").path("subjectDigest").asText().equals(subject(rows)) || !review.path("body").path("decision").asText().equals("APPROVED")) blockers.add("尚無涵蓋目前證據的獨立核准");
            if(b.path("gitPolicy").path("doneWhen").asText().equals("INTEGRATED")) {
                var integration=latest(rows,"INTEGRATION"); if(integration==null || !integration.path("body").path("contractId").asText().equals(cid)) blockers.add("尚未記錄核准整合");
            }
        }
        return new Summary(rows.size(), taskDigest(taskId), subject(rows), blockers.isEmpty(), passed, blockers, rows);
    }
    public List<String> workBlockers(long taskId) {
        var contract=latest(rows(taskId),"CONTRACT");
        if(contract==null) return List.of(); // Claim may be used for discovery; it cannot authorize completion.
        var result=new ArrayList<String>(); var b=contract.path("body");
        if(!b.path("taskDigest").asText().equals(taskDigest(taskId))) result.add("需求版本已變更");
        if(!b.path("questions").isEmpty()) result.add("需求有待決問題");
        for(JsonNode r:b.path("requirements")) if(!r.path("state").asText().equals("CONFIRMED")) result.add("需求未確認："+r.path("id").asText());
        return result;
    }
    public void assertCompletable(long taskId, String codeRef) {
        Summary s=summary(taskId); require(s.canClose(), "不能結案："+String.join("；",s.blockers()));
        JsonNode c=latest(s.events(),"CONTRACT"); require(c.path("body").path("codeRef").asText().equals(codeRef), "完成回報版本與受測版本不符");
    }
    public record Summary(int revision, String taskDigest, String subjectDigest, boolean canClose, List<String> passedCases, List<String> blockers, List<JsonNode> events) {}
}
