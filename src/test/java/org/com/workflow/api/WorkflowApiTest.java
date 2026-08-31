package org.com.workflow.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:sqlite:target/api-test.db?transaction_mode=IMMEDIATE",
        "spring.sql.init.mode=always"
})
class WorkflowApiTest {

    private static final String MODEL_PUBLISH = """
            {"workflowName":"model-publish","steps":[
              {"stepId":"load-model","priority":10,"maxAttempts":1,"dependencies":[]},
              {"stepId":"validate-schema","priority":9,"maxAttempts":2,"dependencies":["load-model"]},
              {"stepId":"write-audit","priority":5,"maxAttempts":1,"dependencies":["load-model"]}
            ]}""";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mockMvc;

    @BeforeEach
    void clean() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.execute("DELETE FROM step_attempt_event");
        jdbc.execute("DELETE FROM step_instance");
        jdbc.execute("DELETE FROM workflow_run");
        jdbc.execute("DELETE FROM step_dependency");
        jdbc.execute("DELETE FROM step_definition");
        jdbc.execute("DELETE FROM workflow_definition");
    }

    private String startRun() throws Exception {
        register();
        String body = mockMvc.perform(post("/workflows/model-publish/runs")
                        .contentType(APPLICATION_JSON).content("{\"requestId\":\"req-1001\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"runId\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private void register() throws Exception {
        mockMvc.perform(post("/workflows").contentType(APPLICATION_JSON).content(MODEL_PUBLISH))
                .andExpect(status().isCreated());
    }

    @Test
    void registersAWorkflow() throws Exception {
        register();
    }

    @Test
    void rejectsACyclicWorkflowWith400() throws Exception {
        String cyclic = """
                {"workflowName":"bad","steps":[
                  {"stepId":"a","priority":1,"maxAttempts":1,"dependencies":["b"]},
                  {"stepId":"b","priority":1,"maxAttempts":1,"dependencies":["a"]}]}""";

        mockMvc.perform(post("/workflows").contentType(APPLICATION_JSON).content(cyclic))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cycle")));
    }

    @Test
    void startsARunAndReplaysItWith200() throws Exception {
        register();
        String first = mockMvc.perform(post("/workflows/model-publish/runs")
                        .contentType(APPLICATION_JSON).content("{\"requestId\":\"req-1001\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/workflows/model-publish/runs")
                        .contentType(APPLICATION_JSON).content("{\"requestId\":\"req-1001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId")
                        .value(first.replaceAll(".*\"runId\"\\s*:\\s*\"([^\"]+)\".*", "$1")));
    }

    @Test
    void returns404ForAnUnknownWorkflow() throws Exception {
        mockMvc.perform(post("/workflows/ghost/runs")
                        .contentType(APPLICATION_JSON).content("{\"requestId\":\"r\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns400ForABlankRequestId() throws Exception {
        register();
        mockMvc.perform(post("/workflows/model-publish/runs")
                        .contentType(APPLICATION_JSON).content("{\"requestId\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void claimsTheHighestPriorityRunnableStep() throws Exception {
        String runId = startRun();

        mockMvc.perform(post("/runs/" + runId + "/claims")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"worker-a\",\"maxCount\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stepId").value("load-model"))
                .andExpect(jsonPath("$[0].attemptNumber").value(1))
                .andExpect(jsonPath("$[1]").doesNotExist());
    }

    @Test
    void returns400ForNonPositiveMaxCount() throws Exception {
        String runId = startRun();

        mockMvc.perform(post("/runs/" + runId + "/claims")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"worker-a\",\"maxCount\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns404WhenClaimingOnAnUnknownRun() throws Exception {
        mockMvc.perform(post("/runs/ghost/claims")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"worker-a\",\"maxCount\":1}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns409WhenCompletingWithTheWrongWorker() throws Exception {
        String runId = startRun();
        mockMvc.perform(post("/runs/" + runId + "/claims").contentType(APPLICATION_JSON)
                .content("{\"workerId\":\"worker-a\",\"maxCount\":1}"));

        mockMvc.perform(post("/runs/" + runId + "/steps/load-model/complete")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"worker-b\",\"attemptNumber\":1,\"result\":\"success\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void returns400ForAnInvalidResultValue() throws Exception {
        String runId = startRun();
        mockMvc.perform(post("/runs/" + runId + "/claims").contentType(APPLICATION_JSON)
                .content("{\"workerId\":\"worker-a\",\"maxCount\":1}"));

        mockMvc.perform(post("/runs/" + runId + "/steps/load-model/complete")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"worker-a\",\"attemptNumber\":1,\"result\":\"maybe\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returns400WhenTheResultFieldIsMissingEntirely() throws Exception {
        String runId = startRun();
        mockMvc.perform(post("/runs/" + runId + "/claims").contentType(APPLICATION_JSON)
                .content("{\"workerId\":\"worker-a\",\"maxCount\":1}"));

        mockMvc.perform(post("/runs/" + runId + "/steps/load-model/complete")
                        .contentType(APPLICATION_JSON)
                        .content("{\"workerId\":\"worker-a\",\"attemptNumber\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value(org.hamcrest.Matchers.containsString("result")));
    }

    @Test
    void returnsTheRunSummarySortedByStepId() throws Exception {
        String runId = startRun();

        mockMvc.perform(get("/runs/" + runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runStatus").value("running"))
                .andExpect(jsonPath("$.steps[0].stepId").value("load-model"))
                .andExpect(jsonPath("$.steps[0].status").value("pending"))
                .andExpect(jsonPath("$.steps[0].attemptCount").value(0))
                .andExpect(jsonPath("$.steps[1].stepId").value("validate-schema"))
                .andExpect(jsonPath("$.steps[2].stepId").value("write-audit"));
    }
}
