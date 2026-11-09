package dev.aiboard.workflow;

import dev.aiboard.acceptance.AcceptanceAuthority;
import dev.aiboard.acceptance.AcceptanceService;
import dev.aiboard.common.BoardException;
import dev.aiboard.event.BoardEvent;
import dev.aiboard.event.BoardEventPublisher;
import dev.aiboard.project.ProjectService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

/** Project configuration revisions are independent of task existence and task claim tokens. */
@Service
public class ProjectWorkflowService {
    public static final List<String> FIELDS = List.of("baseBranch", "integrationTarget", "pullRequestPolicy",
            "requiredChecks", "reviewPolicy", "mergeAuthority", "doneWhen");
    private final JdbcTemplate db;
    private final ProjectService projects;
    private final AcceptanceAuthority authority;
    private final BoardEventPublisher events;
    private final JsonMapper json = JsonMapper.builder().build();

    public ProjectWorkflowService(JdbcTemplate db, ProjectService projects, AcceptanceAuthority authority, BoardEventPublisher events) {
        this.db = db;
        this.projects = projects;
        this.authority = authority;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public Summary get(String projectName) { return getById(project(projectName).id()); }

    @Transactional(readOnly = true)
    public Summary getById(long projectId) {
        var project = projects.getById(projectId);
        var history = history(projectId);
        var latest = history.isEmpty() ? null : history.getLast();
        var confirmed = history.stream().filter(r -> r.state().equals("CONFIRMED")).reduce((a,b) -> b).orElse(null);
        Active active = confirmed == null ? null : new Active(confirmed.version(), confirmed.proposal().gitPolicy(),
                confirmed.proposal().sources(), confirmed.actor(), confirmed.approvalRef(), confirmed.createdAt(), confirmed.digest());
        Legacy legacy = legacy(projectId);
        Draft draft = latest != null && latest.state().equals("DRAFT") ? draft(projectId, latest, active, legacy) : null;
        long revision = latest == null ? 0 : latest.version();
        String status = draft != null ? "DRAFT" : active != null ? "CONFIRMED" : legacy != null ? "LEGACY" : "UNCONFIGURED";
        Map<String,Object> approval = draft == null || !draft.readyForConfirmation() ? null : Map.of(
                "kind", "CONFIRM_PROJECT_WORKFLOW", "projectId", projectId, "draftId", draft.version(),
                "expectedRevision", revision, "previewDigest", draft.previewDigest(), "approved", true);
        String prompt = "請為專案「" + project.name() + "」" + (active == null ? "規劃" : "檢查或修改")
                + " Git 流程。先讀專案文件與已確認設定，列出依據，只詢問缺項與矛盾；完整摘要確認後，透過獨立核准工具保存，不自動合併或發布。";
        return new Summary(projectId, project.name(), revision, status, active, draft, legacy, prompt, approval);
    }

    @Transactional
    public Summary prepare(String projectName, long expectedRevision, PolicyInput input,
                           List<SourceInput> sources, List<QuestionInput> questions, String actor) {
        var project = project(projectName);
        projects.assertActiveForUpdate(project.id());
        var before = getById(project.id());
        require(before.revision() == expectedRevision, "專案流程已更新，請重新讀取後再規劃");
        require(actor != null && !actor.isBlank() && actor.length() <= 100, "缺少可信管理身分");
        Proposal base = base(before);
        var policy = new LinkedHashMap<>(base.gitPolicy());
        Map<String,String> supplied = input == null ? Map.of() : input.values();
        for (var entry : supplied.entrySet()) {
            if (entry.getValue() == null) continue;
            String value = entry.getValue().trim();
            require(value.length() <= 2000, "Git 流程欄位過長：" + entry.getKey());
            if (entry.getKey().equals("doneWhen") && !value.isEmpty())
                require(Set.of("ACCEPTED", "INTEGRATED").contains(value), "DONE 時機必須為 ACCEPTED 或 INTEGRATED");
            policy.put(entry.getKey(), value);
        }
        var normalizedSources = new ArrayList<>(sources == null ? base.sources() : sources);
        require(normalizedSources.stream().noneMatch(Objects::isNull), "來源不可包含空白項目");
        for (String field : FIELDS) {
            if (supplied.get(field) != null && !Objects.equals(policy.get(field), base.gitPolicy().get(field))) {
                boolean freshSource = sources != null && sources.stream().anyMatch(s -> s != null && field.equals(s.field()));
                if (!freshSource) {
                    normalizedSources.removeIf(s -> field.equals(s.field()));
                    if (!policy.get(field).isBlank()) normalizedSources.add(new SourceInput(field, "SUGGESTION", "", "", "代理提出，等待需求負責人確認"));
                }
            }
        }
        require(normalizedSources.size() <= 100, "來源最多 100 筆");
        var cleanSources = normalizedSources.stream().map(this::validateSource).toList();
        var cleanQuestions = questions == null ? base.questions() : questions.stream().map(this::validateQuestion).toList();
        require(cleanQuestions.size() <= 50, "待決問題最多 50 筆");
        var proposal = new Proposal(policy, cleanSources, cleanQuestions);
        long revision = before.revision() + 1;
        long baseVersion = before.active() == null ? 0 : before.active().version();
        String digest = previewDigest(project.id(), revision, baseVersion, proposal);
        insert(project.id(), revision, "DRAFT", baseVersion, proposal, digest, actor, null);
        events.publish(new BoardEvent("project.workflow_changed", Map.of("projectId", project.id(), "revision", revision)));
        return getById(project.id());
    }

    @Transactional
    public Summary confirm(String actor, String payload, String signature) {
        var key = authority.verifySignature(actor, payload, signature);
        JsonNode approval = json.readTree(payload);
        require(approval.isObject() && "CONFIRM_PROJECT_WORKFLOW".equals(approval.path("kind").asText()), "核准種類不符");
        require(approval.path("projectId").isIntegralNumber(), "核准專案 ID 不合法");
        long projectId = approval.path("projectId").asLong();
        authority.assertScope(key, projectId, "PROJECT_POLICY");
        projects.assertActiveForUpdate(projectId);
        var current = getById(projectId);
        var draft = current.draft();
        require(draft != null, "沒有待確認的專案流程草稿");
        require(approval.path("expectedRevision").isIntegralNumber() && approval.path("expectedRevision").asLong() == current.revision(), "專案流程版本已變更，請重新檢視");
        require(approval.path("draftId").isIntegralNumber() && approval.path("draftId").asLong() == draft.version(), "核准草稿版本不符");
        require(approval.path("approved").isBoolean() && approval.path("approved").asBoolean(), "必須明確確認完整草稿");
        require(approval.path("previewDigest").isTextual() && draft.previewDigest().equals(approval.path("previewDigest").asText()), "核准摘要與草稿不符");
        require(draft.readyForConfirmation(), "草稿仍有缺項或未決問題，不能生效");
        require(approval.path("approvalRef").isTextual(), "必須提供核准紀錄");
        String ref = approval.path("approvalRef").asText().trim();
        require(!ref.isEmpty() && ref.length() <= 1000, "核准紀錄不可空白或超過 1000 字");
        var proposal = new Proposal(draft.gitPolicy(), draft.sources(), draft.questions());
        insert(projectId, current.revision() + 1, "CONFIRMED", draft.baseVersion(), proposal,
                draft.previewDigest(), actor, ref);
        events.publish(new BoardEvent("project.workflow_changed", Map.of("projectId", projectId, "revision", current.revision() + 1)));
        return getById(projectId);
    }

    /** Legacy signed policies remain historical compatibility data until explicitly upgraded. */
    public Legacy legacy(long projectId) {
        var results = db.query("SELECT event_id,payload FROM acceptance_event WHERE project_id=? AND kind='PROJECT_POLICY' ORDER BY id DESC FETCH FIRST 1 ROW ONLY",
                (rs, index) -> new Legacy(policy(json.readTree(rs.getString("payload")).path("body").path("gitPolicy")), rs.getString("event_id")), projectId);
        return results.isEmpty() ? null : results.getFirst();
    }

    public boolean hasRevisions(long projectId) {
        return db.queryForObject("SELECT COUNT(*) FROM project_workflow_revision WHERE project_id=?", Long.class, projectId) > 0;
    }

    /** One source for the effective policy, shared by planning and acceptance. */
    public Effective effective(long projectId) {
        var rows = db.query("SELECT revision,proposal_json FROM project_workflow_revision WHERE project_id=? AND state='CONFIRMED' ORDER BY revision DESC FETCH FIRST 1 ROW ONLY",
                (rs,index) -> new Effective(rs.getLong("revision"), policy(json.readTree(rs.getString("proposal_json")).path("gitPolicy"))), projectId);
        if (!rows.isEmpty()) return rows.getFirst();
        var old = legacy(projectId);
        return old == null ? null : new Effective(0, old.gitPolicy());
    }

    private Draft draft(long projectId, Revision row, Active active, Legacy legacy) {
        var proposal = row.proposal();
        var missing = FIELDS.stream().filter(f -> proposal.gitPolicy().getOrDefault(f, "").isBlank()).toList();
        var baseline = active != null ? active.gitPolicy() : legacy != null ? legacy.gitPolicy() : Map.<String,String>of();
        var changes = FIELDS.stream().filter(f -> !Objects.equals(baseline.getOrDefault(f,""), proposal.gitPolicy().getOrDefault(f,"")))
                .map(f -> new Change(f, baseline.getOrDefault(f,""), proposal.gitPolicy().getOrDefault(f,""))).toList();
        return new Draft(row.version(), row.baseVersion(), proposal.gitPolicy(), proposal.sources(), proposal.questions(), missing,
                missing.isEmpty() && proposal.questions().isEmpty(), row.digest(), changes);
    }

    private Proposal base(Summary before) {
        if (before.draft() != null) return new Proposal(before.draft().gitPolicy(), before.draft().sources(), before.draft().questions());
        if (before.active() != null) return new Proposal(before.active().gitPolicy(), before.active().sources(), List.of());
        if (before.legacy() != null) return new Proposal(before.legacy().gitPolicy(), List.of(), List.of());
        return new Proposal(policy(json.createObjectNode()), List.of(), List.of());
    }
    private SourceInput validateSource(SourceInput input) {
        require(input != null && input.field() != null && FIELDS.contains(input.field()), "來源必須對應 Git 流程欄位");
        require(Set.of("PROJECT_RULE", "USER_DECISION", "SUGGESTION").contains(Objects.toString(input.kind(), "")), "未知來源種類");
        String ref = clean(input.ref()), version = clean(input.version()), detail = clean(input.detail());
        require(!input.kind().equals("PROJECT_RULE") || (!ref.isEmpty() && !version.isEmpty()), "專案規則來源必須附文件與版本");
        require(ref.length() <= 1000 && version.length() <= 200 && detail.length() <= 2000, "來源內容過長");
        return new SourceInput(input.field(), input.kind(), ref, version, detail);
    }
    private QuestionInput validateQuestion(QuestionInput input) {
        require(input != null && input.field() != null && FIELDS.contains(input.field()) && !clean(input.question()).isEmpty() && clean(input.question()).length() <= 1000, "待決問題必須對應欄位並提供問題");
        return new QuestionInput(input.field(), input.question().trim());
    }
    private Map<String,String> policy(JsonNode node) {
        var result = new LinkedHashMap<String,String>();
        for (String field : FIELDS) result.put(field, node.path(field).asText(""));
        return result;
    }
    private String previewDigest(long projectId, long revision, long base, Proposal proposal) {
        var data = new ArrayList<Object>(List.of(projectId, revision, base));
        FIELDS.forEach(f -> data.add(proposal.gitPolicy().getOrDefault(f,"")));
        data.add(proposal.sources().stream().map(s -> List.of(s.field(), s.kind(), clean(s.ref()), clean(s.version()), clean(s.detail()))).toList());
        data.add(proposal.questions().stream().map(q -> List.of(q.field(), q.question())).toList());
        return AcceptanceService.digest(json.writeValueAsString(data));
    }
    private List<Revision> history(long projectId) {
        return db.query("SELECT * FROM project_workflow_revision WHERE project_id=? ORDER BY revision", (rs,index) -> {
            Proposal proposal = json.readValue(rs.getString("proposal_json"), Proposal.class);
            return new Revision(rs.getLong("revision"), rs.getString("state"), rs.getLong("base_revision"), proposal,
                    rs.getString("preview_digest"), rs.getString("actor"), rs.getString("approval_ref"), rs.getString("created_at"));
        }, projectId);
    }
    private void insert(long projectId, long revision, String state, long base, Proposal proposal, String digest, String actor, String approvalRef) {
        db.update("INSERT INTO project_workflow_revision(project_id,revision,state,base_revision,proposal_json,preview_digest,actor,approval_ref) VALUES(?,?,?,?,?,?,?,?)",
                projectId, revision, state, base, json.writeValueAsString(proposal), digest, actor, approvalRef);
    }
    private ProjectService.ProjectDto project(String name) {
        return projects.findByNameIgnoreCase(name).orElseThrow(() -> new BoardException("找不到專案：" + name));
    }
    private String clean(String value) { return value == null ? "" : value.trim(); }
    private void require(boolean valid, String message) { if (!valid) throw new BoardException(message); }

    public record PolicyInput(String baseBranch, String integrationTarget, String pullRequestPolicy, String requiredChecks,
                              String reviewPolicy, String mergeAuthority, String doneWhen) {
        Map<String,String> values() {
            var result = new LinkedHashMap<String,String>();
            result.put("baseBranch",baseBranch); result.put("integrationTarget",integrationTarget);
            result.put("pullRequestPolicy",pullRequestPolicy); result.put("requiredChecks",requiredChecks);
            result.put("reviewPolicy",reviewPolicy); result.put("mergeAuthority",mergeAuthority); result.put("doneWhen",doneWhen);
            return result;
        }
    }
    public record SourceInput(String field, String kind, String ref, String version, String detail) {}
    public record QuestionInput(String field, String question) {}
    public record Proposal(Map<String,String> gitPolicy, List<SourceInput> sources, List<QuestionInput> questions) {}
    private record Revision(long version, String state, long baseVersion, Proposal proposal, String digest, String actor, String approvalRef, String createdAt) {}
    public record Change(String field, String before, String after) {}
    public record Active(long version, Map<String,String> gitPolicy, List<SourceInput> sources, String confirmedBy, String approvalRef, String confirmedAt, String previewDigest) {}
    public record Draft(long version, long baseVersion, Map<String,String> gitPolicy, List<SourceInput> sources, List<QuestionInput> questions,
                        List<String> missingFields, boolean readyForConfirmation, String previewDigest, List<Change> changes) {}
    public record Legacy(Map<String,String> gitPolicy, String eventId) {}
    public record Effective(long version, Map<String,String> gitPolicy) {}
    public record Summary(long projectId, String projectName, long revision, String status, Active active, Draft draft, Legacy legacy,
                          String planningPrompt, Map<String,Object> approvalPayload) {}
}
