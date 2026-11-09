package dev.aiboard.acceptance;

import dev.aiboard.task.*;
import dev.aiboard.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.LongStream;
import static org.assertj.core.api.Assertions.*;

/** Real H2 journal + signed identities, never a mocked acceptance gate. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:acceptance-signed;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=validate", "logging.file.name=/tmp/ai-board-acceptance-signed.log"})
public class AcceptanceServiceIntegrationTest {
    static final String REF = "a".repeat(40);
    static final String SECRET = "isolated-fixture-key-32-characters-minimum";
    static final JsonMapper JSON = JsonMapper.builder().build();
    @DynamicPropertySource
    public static void authorities(DynamicPropertyRegistry properties) {
        properties.add("BOARD_ACCEPTANCE_AUTHORITIES", () -> {
            var ids = LongStream.range(1, 10000).mapToObj(Long::toString).toList();
            return JSON.writeValueAsString(Map.of(
                "owner", Map.of("secret", SECRET, "projectIds", ids, "permissions", List.of("CONTRACT", "EXCEPTION", "INTEGRATION", "PROJECT_POLICY")),
                "ci", Map.of("secret", SECRET + "-ci", "projectIds", ids, "permissions", List.of("RUN"), "runnerProfiles", List.of("isolated-browser")),
                "reviewer", Map.of("secret", SECRET + "-reviewer", "projectIds", ids, "permissions", List.of("REVIEW")),
                "developer", Map.of("secret", SECRET + "-developer", "projectIds", ids, "permissions", List.of("RUN"), "runnerProfiles", List.of("isolated-browser")),
                "outsider", Map.of("secret", SECRET + "-outsider", "projectIds", List.of("99999"), "permissions", List.of("CONTRACT"))));
        });
    }
    /** Test-only signed fixture: real journal API, isolated non-UI contract and independent signers. */
    public static void approveFixture(AcceptanceService service, Task task) throws Exception {
        Map<String,Object> git=Map.of("baseBranch","work","integrationTarget","review","reviewPolicy","independent","mergeAuthority","human","pullRequestPolicy","manual","requiredChecks","fixture","doneWhen","ACCEPTED");
        String cid=UUID.randomUUID().toString();
        Map<String,Object> contract=new LinkedHashMap<>();
        contract.putAll(Map.of("sourceRef","fixture-spec","sourceVersion","v1","codeRef",REF,"taskDigest",service.taskDigest(task.getId()),"flowRef","fixture-flow","flowVersion","v1","implementationActor",task.getAssignee(),"requirements",List.of(Map.of("id","R1","state","CONFIRMED","acceptance","isolated prerequisite verified")),"questions",List.of(),"assumptions",List.of()));
        contract.put("cases",List.of(Map.of("id","C1","kind","API","scenario","NORMAL","requirementIds",List.of("R1"))));
        contract.put("gitPolicy",git); contract.put("browserApplicable",false); contract.put("browserExemptionReason","fixture only tests non-UI prerequisite lifecycle");
        recordFixture(service,task,"owner","PROJECT_POLICY",UUID.randomUUID().toString(),Map.of("gitPolicy",git));
        recordFixture(service,task,"owner","CONTRACT",cid,contract);
        Map<String,Object> run=new LinkedHashMap<>();
        run.putAll(Map.of("contractId",cid,"codeRef",REF,"caseId","C1","runId",UUID.randomUUID().toString(),"runnerProfile","isolated-browser","result","PASSED","artifactUri","ci://fixture","artifactSha256","b".repeat(64),"steps","isolated prerequisite check","actual","expected fixture observed")); run.put("environment","ISOLATED");
        recordFixture(service,task,"ci","RUN",UUID.randomUUID().toString(),run);
        recordFixture(service,task,"reviewer","REVIEW",UUID.randomUUID().toString(),Map.of("contractId",cid,"codeRef",REF,"subjectDigest",service.summary(task.getId()).subjectDigest(),"reason","independent fixture review","decision","APPROVED"));
    }
    static void recordFixture(AcceptanceService service, Task task, String actor, String kind, String id, Map<String,Object> body) throws Exception {
        String payload=JSON.writeValueAsString(Map.of("eventId",id,"taskId",task.getId(),"projectId",task.getProjectId(),"expectedRevision",service.summary(task.getId()).revision(),"kind",kind,"body",body));
        String key=actor.equals("ci")?SECRET+"-ci":actor.equals("reviewer")?SECRET+"-reviewer":SECRET;
        Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        service.record(actor,payload,HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))));
    }
    @Autowired AcceptanceService acceptance;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired TaskCompleteService completion;
    @Autowired TaskRepository repository;
    @Autowired JdbcTemplate db;
    long projectId, taskId;
    String token, contractId;
    Map<String, Object> contract;

    @BeforeEach void setup() {
        String project = "signed-" + UUID.randomUUID();
        projectId = projects.createProject(project, null).project().id();
        tasks.createTasks(projectId, List.of(new TaskService.TaskInput("persist user role", "confirmed source", "BACKEND")));
        var claim = tasks.claimNextTask(project, "BACKEND", "developer");
        taskId = claim.task().id(); token = claim.claimToken();
        contract = new LinkedHashMap<>(Map.of(
            "sourceRef", "requirements/users.md", "sourceVersion", "req-v1", "codeRef", REF,
            "taskDigest", acceptance.taskDigest(taskId), "flowRef", "qa/user-role.md", "flowVersion", "flow-v1",
            "implementationActor", "developer", "requirements", List.of(Map.of("id", "R1", "state", "CONFIRMED", "acceptance", "role survives reload and forbidden action is rejected")),
            "questions", List.of(), "assumptions", List.of()));
        contract.put("cases", List.of(Map.of("id", "C1", "kind", "BROWSER", "scenario", "NORMAL", "requirementIds", List.of("R1"))));
        contract.put("scenarioExemptions", Map.of("ERROR", "owner-approved ledger-only fixture", "BOUNDARY", "owner-approved ledger-only fixture", "PERMISSION", "owner-approved ledger-only fixture", "FAILURE", "owner-approved ledger-only fixture", "PERSISTENCE", "owner-approved ledger-only fixture", "DOWNSTREAM", "owner-approved ledger-only fixture"));
        contract.put("gitPolicy", Map.of("baseBranch", "work", "integrationTarget", "review", "reviewPolicy", "independent", "mergeAuthority", "human", "pullRequestPolicy", "manual", "requiredChecks", "browser", "doneWhen", "ACCEPTED"));
    }
    String secret(String actor) { return actor.equals("ci") ? SECRET + "-ci" : actor.equals("reviewer") ? SECRET + "-reviewer" : actor.equals("developer") ? SECRET + "-developer" : actor.equals("outsider") ? SECRET + "-outsider" : SECRET; }
    String sign(String actor, String payload) throws Exception {
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret(actor).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    String payload(String kind, Map<String, Object> body, long project) {
        return JSON.writeValueAsString(Map.of("eventId", UUID.randomUUID().toString(), "taskId", taskId,
            "projectId", project, "expectedRevision", acceptance.summary(taskId).revision(), "kind", kind, "body", body));
    }
    String record(String actor, String kind, Map<String,Object> body) throws Exception {
        String p = payload(kind, body, projectId); acceptance.record(actor, p, sign(actor,p));
        return JSON.readTree(p).path("eventId").asText();
    }
    void contract() throws Exception {
        record("owner", "PROJECT_POLICY", Map.of("gitPolicy", contract.get("gitPolicy")));
        contractId = record("owner", "CONTRACT", contract);
    }
    Map<String,Object> runBody(String result) {
        var body = new LinkedHashMap<String,Object>();
        body.putAll(Map.of("contractId", contractId, "codeRef", REF, "caseId", "C1", "runId", UUID.randomUUID().toString(),
            "runnerProfile", "isolated-browser", "result", result, "artifactUri", "ci://fixtures/browser.json", "artifactSha256", "a".repeat(64), "steps", "create > role > login > reject forbidden > reload", "actual", "persisted and denied"));
        body.put("environment", "ISOLATED"); return body;
    }
    void run(String result) throws Exception { record("ci", "RUN", runBody(result)); }
    void review() throws Exception { record("reviewer", "REVIEW", Map.of("contractId", contractId, "codeRef", REF, "subjectDigest", acceptance.summary(taskId).subjectDigest(), "reason", "raw requirement and evidence reviewed", "decision", "APPROVED")); }
    void complete() { completion.completeTask(taskId, token, "implementation supplied", List.of(new TaskCompleteService.VerificationInput("self test", "PASSED", null)), "Users.java", REF, null); }
    void denied() { assertThatThrownBy(this::complete).hasMessageContaining("不能結案"); assertThat(repository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.IN_PROGRESS); }

    @Test void selfDeclarationWithoutContractCannotComplete() { denied(); }
    @Test void forgedSignatureAndWrongProjectScopeAreRejected() throws Exception {
        String p = payload("CONTRACT", contract, projectId);
        assertThatThrownBy(() -> acceptance.record("owner", p, "0".repeat(64))).hasMessageContaining("簽章");
        assertThatThrownBy(() -> acceptance.record("outsider", p, sign("outsider",p))).hasMessageContaining("權限");
        String wrong = payload("CONTRACT", contract, projectId + 1);
        assertThatThrownBy(() -> acceptance.record("owner", wrong, sign("owner",wrong))).hasMessageContaining("專案不符");
        assertThat(acceptance.summary(taskId).revision()).isZero(); denied();
    }
    @Test void missingCasesAreRejectedAndUncoveredRequirementBlocks() throws Exception {
        contract.put("cases", List.of());
        assertThatThrownBy(this::contract).hasMessageContaining("測試案例");
        contract.put("cases", List.of(Map.of("id", "C1", "kind", "BROWSER", "scenario", "flow", "requirementIds", List.of("R1"))));
        contract.put("requirements", List.of(Map.of("id","R1","state","CONFIRMED","acceptance","persist"), Map.of("id","R2","state","CONFIRMED","acceptance","denied")));
        contract(); run("PASSED"); review(); denied();
        assertThat(acceptance.summary(taskId).blockers()).anyMatch(s -> s.contains("需求漏驗"));
    }
    @Test void failedAndNotRunCannotCompleteEvenWithIndependentApproval() throws Exception {
        contract(); run("FAILED"); review(); denied(); run("NOT_RUN"); review(); denied();
    }
    @Test void wrongCodeRefAndUnapprovedRunnerAreRejected() throws Exception {
        contract(); var body=runBody("PASSED"); body.put("codeRef","b".repeat(40));
        assertThatThrownBy(() -> record("ci","RUN",body)).hasMessageContaining("受測程式版本不符");
        body.put("codeRef",REF); body.put("runnerProfile","arbitrary-shell");
        assertThatThrownBy(() -> record("ci","RUN",body)).hasMessageContaining("未核准的執行器"); denied();
    }
    @Test void requirementMutationAndPendingBusinessQuestionsInvalidateAcceptance() throws Exception {
        contract(); run("PASSED"); review();
        db.update("UPDATE task SET description=? WHERE id=?", "changed business rule", taskId);
        denied(); assertThat(acceptance.summary(taskId).blockers()).anyMatch(s -> s.contains("需求已變更"));
    }
    @Test void omittedBrowserScenariosNeedOwnerApprovedReason() throws Exception {
        contract.remove("scenarioExemptions"); contract(); run("PASSED"); review(); denied();
        assertThat(acceptance.summary(taskId).blockers()).anyMatch(s -> s.contains("PERMISSION"));
    }
    @Test void questionsAndAssumptionsNeverBecomeConfirmedRequirements() throws Exception {
        contract.put("questions", List.of("who may assign admin?"));
        contract.put("requirements", List.of(Map.of("id","R1","state","ASSUMPTION","acceptance","guessed permission")));
        contract(); run("PASSED"); review(); denied();
        assertThat(acceptance.summary(taskId).blockers()).anyMatch(s -> s.contains("人類決定"));
    }
    @Test void changedEvidenceMakesPriorReviewStaleAndJournalIsAppendOnly() throws Exception {
        contract(); run("PASSED"); review(); assertThat(acceptance.summary(taskId).canClose()).isTrue();
        var before = db.queryForList("SELECT payload FROM acceptance_event WHERE task_id=? ORDER BY id", String.class, taskId);
        run("PASSED"); denied();
        var after = db.queryForList("SELECT payload FROM acceptance_event WHERE task_id=? ORDER BY id", String.class, taskId);
        assertThat(after).startsWith(before.toArray(String[]::new)); assertThat(after).hasSize(before.size()+1);
        review(); complete(); assertThat(repository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
    }
    @Test void authorCannotSignRunnerEvidenceAndRunnerCannotApproveOwnEvidence() throws Exception {
        contract();
        assertThatThrownBy(() -> record("developer", "RUN", runBody("PASSED"))).hasMessageContaining("開發代理聲明");
        run("PASSED");
        var review = Map.<String,Object>of("contractId", contractId, "codeRef", REF, "subjectDigest", acceptance.summary(taskId).subjectDigest(), "reason", "self approval attempt", "decision", "APPROVED");
        assertThatThrownBy(() -> record("developer", "REVIEW", review)).hasMessageContaining("權限");
        assertThatThrownBy(() -> record("ci", "REVIEW", review)).hasMessageContaining("權限");
        denied();
    }
    @Test void signedReplayCannotAppendAndJournalReportsVerifiedIdentity() throws Exception {
        String p=payload("PROJECT_POLICY", Map.of("gitPolicy",contract.get("gitPolicy")), projectId);
        acceptance.record("owner",p,sign("owner",p));
        assertThatThrownBy(() -> acceptance.record("owner",p,sign("owner",p))).hasMessageContaining("紀錄已更新");
        var event=acceptance.summary(taskId).events().getFirst();
        assertThat(event.path("_verifiedActor").asText()).isEqualTo("owner");
        assertThat(event.path("_receivedAt").asText()).isNotBlank();
        assertThat(acceptance.summary(taskId).revision()).isEqualTo(1);
    }
    @Test void unresolvedContractSkipsOnlyAffectedWorkAndAllowsUnrelatedClaim() throws Exception {
        tasks.updateStatus(taskId, "BLOCKED", "待確認", token);
        tasks.updateStatus(taskId, "TODO", "重新排程", token);
        contract.put("questions", List.of("who may assign admin?"));
        contract();
        tasks.createTasks(projectId, List.of(new TaskService.TaskInput("unrelated independent work", null, "BACKEND")));
        assertThat(acceptance.workBlockers(taskId)).contains("需求有待決問題");
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(tasks, "acceptance")).isNotNull();
        String name = projects.getById(projectId).name();
        var claim = tasks.claimNextTask(name, "BACKEND", "another-worker");
        assertThat(claim.task()).isNotNull();
        assertThat(claim.task().id()).isNotEqualTo(taskId);
        assertThat(claim.task().title()).isEqualTo("unrelated independent work");
        assertThat(repository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.TODO);
    }
    @Test void mutableBranchReferenceCannotIdentifyTestedVersion() throws Exception {
        contract.put("codeRef", "main");
        assertThatThrownBy(this::contract).hasMessageContaining("完整 Git SHA");
        denied();
    }
    @Test void newlineBoundariesCannotHideChangedRequirementFields() {
        db.update("UPDATE task SET title=?,description=? WHERE id=?", "first\nsecond", "third", taskId);
        String original=acceptance.taskDigest(taskId);
        db.update("UPDATE task SET title=?,description=? WHERE id=?", "first", "second\nthird", taskId);
        assertThat(acceptance.taskDigest(taskId)).isNotEqualTo(original);
    }
    @Test void sharedKeysAndRunnerMixedPrivilegesFailClosed() {
        var scoped=List.of(Long.toString(projectId));
        String shared=JSON.writeValueAsString(Map.of("a",Map.of("secret",SECRET,"projectIds",scoped,"permissions",List.of("CONTRACT")),"b",Map.of("secret",SECRET,"projectIds",scoped,"permissions",List.of("REVIEW"))));
        var duplicate=new AcceptanceService(db,repository,projects,shared);
        assertThatThrownBy(() -> duplicate.record("a","{}","0".repeat(64))).hasMessageContaining("不可共用");
        String mixed=JSON.writeValueAsString(Map.of("runner",Map.of("secret",SECRET,"projectIds",scoped,"permissions",List.of("RUN","REVIEW"))));
        var conflicting=new AcceptanceService(db,repository,projects,mixed);
        assertThatThrownBy(() -> conflicting.record("runner","{}","0".repeat(64))).hasMessageContaining("不得兼任");
    }
    @Test void authorWithOwnerPermissionStillCannotGrantOwnException() throws Exception {
        db.update("UPDATE task SET assignee=? WHERE id=?","owner",taskId);
        contract.put("implementationActor","owner"); contract();
        assertThatThrownBy(() -> record("owner","EXCEPTION",Map.of("contractId",contractId,"codeRef",REF,"caseId","C1","reason","self waiver","approvalRef","human-reference")))
            .hasMessageContaining("不得自行核准例外");
        assertThat(acceptance.summary(taskId).canClose()).isFalse();
    }
    @Test void laterFailedAcceptanceBlocksDependentClaimWithoutRewritingHistoricalDone() throws Exception {
        contract(); run("PASSED"); review(); complete();
        tasks.createTasks(projectId,List.of(
            new TaskService.TaskInput("dependent work",null,"BACKEND",List.of(),List.of(taskId)),
            new TaskService.TaskInput("independent work after invalidation",null,"BACKEND")));
        run("FAILED");
        assertThat(repository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        var claimed=tasks.claimNextTask(projects.getById(projectId).name(),"BACKEND","next-worker");
        assertThat(claimed.task().title()).isEqualTo("independent work after invalidation");
        var blocked=tasks.claimNextTask(projects.getById(projectId).name(),"BACKEND","waiting-worker");
        assertThat(blocked.claimed()).isFalse();
        assertThat(blocked.blockedByDependency()).isTrue();
        assertThat(blocked.blockedCandidates()).anySatisfy(c -> assertThat(c.waitingFor()).anySatisfy(t -> {
            assertThat(t.id()).isEqualTo(taskId); assertThat(t.status()).isEqualTo("DONE");
        }));
    }
    @Test void signedControlledRunIndependentReviewAndExactVersionPermitDone() throws Exception {
        contract(); run("PASSED"); review();
        assertThatThrownBy(() -> acceptance.assertCompletable(taskId,"b".repeat(40))).hasMessageContaining("受測版本不符");
        complete(); assertThat(repository.findById(taskId).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM acceptance_event WHERE task_id=?", Long.class, taskId)).isEqualTo(4);
    }
}
