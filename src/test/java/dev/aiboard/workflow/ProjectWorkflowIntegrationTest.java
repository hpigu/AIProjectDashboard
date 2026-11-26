package dev.aiboard.workflow;

import dev.aiboard.acceptance.AcceptanceService;
import dev.aiboard.acceptance.AcceptanceServiceIntegrationTest;
import dev.aiboard.project.ProjectService;
import dev.aiboard.task.TaskCompleteService;
import dev.aiboard.task.TaskService;
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
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:project-workflow;DB_CLOSE_DELAY=-1", "logging.file.name=/tmp/board-workflow-integration.log"})
class ProjectWorkflowIntegrationTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String KEY="isolated-fixture-key-32-characters-minimum", CODE="a".repeat(40);
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) { AcceptanceServiceIntegrationTest.authorities(registry); }
    @Autowired ProjectWorkflowService workflows;
    @Autowired ProjectService projects;
    @Autowired TaskService tasks;
    @Autowired AcceptanceService acceptance;
    @Autowired TaskCompleteService completion;
    @Autowired JdbcTemplate db;
    String name;
    long projectId;
    @BeforeEach void create() { name="workflow-"+UUID.randomUUID(); projectId=projects.createProject(name,null).project().id(); }
    ProjectWorkflowService.PolicyInput policy() { return new ProjectWorkflowService.PolicyInput("feature-base","review-target","PR required","unit + API + browser","independent reviewer","human approval","INTEGRATED"); }
    ProjectWorkflowService.Summary prepare(long revision) { return workflows.prepare(name,revision,policy(),List.of(),List.of(),"configured-management-credential"); }
    String sign(String key,String payload) throws Exception {
        var mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
    String approval(ProjectWorkflowService.Summary summary) {
        var values=new LinkedHashMap<String,Object>();
        values.put("kind","CONFIRM_PROJECT_WORKFLOW"); values.put("projectId",projectId);
        values.put("draftId",summary.draft().version()); values.put("expectedRevision",summary.revision());
        values.put("previewDigest",summary.draft().previewDigest()); values.put("approved",true); values.put("approvalRef","owner-terminal-confirmation");
        return JSON.writeValueAsString(values);
    }
    ProjectWorkflowService.Summary confirm(ProjectWorkflowService.Summary summary) throws Exception {
        String payload=approval(summary); return workflows.confirm("owner",payload,sign(KEY,payload));
    }
    @Test void emptyProjectCanPlanConfirmAndReuseWithoutCreatingTasks() throws Exception {
        var empty=workflows.get(name); assertThat(empty.revision()).isZero(); assertThat(empty.status()).isEqualTo("UNCONFIGURED");
        var draft=prepare(0); assertThat(draft.active()).isNull(); assertThat(draft.draft().readyForConfirmation()).isTrue();
        var active=confirm(draft); assertThat(active.active().version()).isEqualTo(2); assertThat(active.draft()).isNull();
        assertThat(active.active().confirmedBy()).isEqualTo("owner");
        assertThat(workflows.get(name.toUpperCase()).active()).isEqualTo(active.active());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM task WHERE project_id=?",Long.class,projectId)).isZero();
    }
    @Test void missingFieldsAndQuestionsCannotBeConfirmedButCanBeFilledWithoutRepeatingOtherFields() throws Exception {
        var first=workflows.prepare(name,0,new ProjectWorkflowService.PolicyInput("base",null,null,null,null,null,null),null,
                List.of(new ProjectWorkflowService.QuestionInput("integrationTarget","公司整合分支是哪個？")),"manager");
        assertThat(first.draft().missingFields()).hasSize(6); assertThat(first.approvalPayload()).isNull();
        assertThatThrownBy(()->confirm(first)).hasMessageContaining("缺項");
        var second=workflows.prepare(name,1,new ProjectWorkflowService.PolicyInput(null,"target","MR","tests","review","owner","ACCEPTED"),null,List.of(),"manager");
        assertThat(second.draft().gitPolicy().get("baseBranch")).isEqualTo("base");
        assertThat(confirm(second).active().gitPolicy().get("doneWhen")).isEqualTo("ACCEPTED");
    }
    @Test void draftDoesNotReplaceActiveAndChangedValueDoesNotInheritOldRuleEvidence() throws Exception {
        var active=confirm(prepare(0));
        var modified=workflows.prepare(name,2,new ProjectWorkflowService.PolicyInput("other-base",null,null,null,null,null,null),null,null,"manager");
        assertThat(modified.active()).isEqualTo(active.active()); assertThat(modified.draft().baseVersion()).isEqualTo(2);
        assertThat(modified.draft().sources()).anySatisfy(s->{assertThat(s.field()).isEqualTo("baseBranch");assertThat(s.kind()).isEqualTo("SUGGESTION");});
        assertThat(modified.draft().changes()).containsExactly(new ProjectWorkflowService.Change("baseBranch","feature-base","other-base"));
    }
    @Test void oldSignatureWrongDigestWrongScopeAndManagerKeyCannotApprove() throws Exception {
        var first=prepare(0); String payload=approval(first);
        assertThatThrownBy(()->workflows.confirm("owner",payload,"0".repeat(64))).hasMessageContaining("簽章");
        assertThatThrownBy(()->workflows.confirm("outsider",payload,sign(KEY+"-outsider",payload))).hasMessageContaining("權限");
        assertThatThrownBy(()->workflows.confirm("ci",payload,sign(KEY+"-ci",payload))).hasMessageContaining("權限");
        String wrong=payload.replace(first.draft().previewDigest(),"0".repeat(64));
        assertThatThrownBy(()->workflows.confirm("owner",wrong,sign(KEY,wrong))).hasMessageContaining("摘要");
        prepare(1);
        assertThatThrownBy(()->workflows.confirm("owner",payload,sign(KEY,payload))).hasMessageContaining("版本");
        var current=workflows.get(name); confirm(current);
        assertThatThrownBy(()->confirm(current)).hasMessageContaining("沒有待確認");
    }
    @Test void projectIsolationArchiveAndInvalidRuleEvidenceAreEnforced() throws Exception {
        prepare(0); var other=projects.createProject(name+"-other",null).project();
        assertThat(workflows.getById(other.id()).revision()).isZero();
        assertThatThrownBy(()->workflows.prepare(name,1,null,List.of(new ProjectWorkflowService.SourceInput("baseBranch","PROJECT_RULE","","","claimed rule")),null,"manager")).hasMessageContaining("文件與版本");
        db.update("UPDATE project SET status='ARCHIVED' WHERE id=?",projectId);
        assertThatThrownBy(()->prepare(1)).hasMessageContaining("封存");
    }
    @Test void sharedCanonicalDigestMatchesWorkersAndNullSourcesFailClearly() {
        String vector="[1,1,0,\"base\",\"\",\"\",\"\",\"\",\"\",\"\",[[\"baseBranch\",\"PROJECT_RULE\",\"AGENTS.md\",\"v1\",\"rule\"]],[]]";
        assertThat(AcceptanceService.digest(vector)).isEqualTo("f3ebf5b15ddaaf87880dc3b7536ca59e8a62758acf13f3d869466335dbb284a5");
        assertThatThrownBy(()->workflows.prepare(name,0,policy(),Arrays.asList((ProjectWorkflowService.SourceInput)null),List.of(),"manager"))
                .hasMessageContaining("來源不可");
        assertThat(workflows.get(name).revision()).isZero();
    }
    @Test void concurrentDraftsWithSameRevisionOnlySaveOne() throws Exception {
        var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        Callable<Boolean> action=()->{start.await(); try {prepare(0);return true;} catch(RuntimeException e){return false;}};
        try {var a=pool.submit(action);var b=pool.submit(action);start.countDown();assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);}
        finally {pool.shutdownNow();}
        assertThat(workflows.get(name).revision()).isEqualTo(1);
    }
    @Test void workflowVersionIsBoundToAcceptanceEvenWhenSettingsReturnToSameValues() throws Exception {
        var active=confirm(prepare(0));
        tasks.createTasks(projectId,List.of(new TaskService.TaskInput("work",null,"BACKEND")));
        var claim=tasks.claimNextTask(name,"BACKEND","developer"); long taskId=claim.task().id();
        Map<String,Object> body=new LinkedHashMap<>(Map.of("sourceRef","spec","sourceVersion","v1","codeRef",CODE,
                "taskDigest",acceptance.taskDigest(taskId),"flowRef","qa-flow","flowVersion","v1","implementationActor","developer"));
        body.put("requirements",List.of(Map.of("id","R1","state","CONFIRMED","acceptance","API works")));
        body.put("cases",List.of(Map.of("id","C1","kind","API","scenario","NORMAL","requirementIds",List.of("R1"))));
        body.put("questions",List.of());body.put("assumptions",List.of());body.put("browserApplicable",false);body.put("browserExemptionReason","non-UI fixture");
        body.put("gitPolicy",active.active().gitPolicy());
        assertThatThrownBy(()->record(taskId,"CONTRACT",body)).hasMessageContaining("流程版本");
        body.put("workflowVersion",2); record(taskId,"CONTRACT",body);
        assertThat(acceptance.summary(taskId).blockers()).noneMatch(s->s.contains("流程版本"));
        var draft=workflows.prepare(name,2,null,null,null,"manager");
        assertThat(acceptance.summary(taskId).blockers()).noneMatch(s->s.contains("流程版本"));
        confirm(draft);
        assertThat(acceptance.summary(taskId).blockers()).anyMatch(s->s.contains("流程版本"));
        assertThatThrownBy(()->record(taskId,"PROJECT_POLICY",Map.of("gitPolicy",active.active().gitPolicy()))).hasMessageContaining("草稿");
    }
    void record(long taskId,String kind,Map<String,Object> body) throws Exception {
        String payload=JSON.writeValueAsString(Map.of("eventId",UUID.randomUUID().toString(),"projectId",projectId,"taskId",taskId,"expectedRevision",acceptance.summary(taskId).revision(),"kind",kind,"body",body));
        acceptance.record("owner",payload,sign(KEY,payload));
    }
}
