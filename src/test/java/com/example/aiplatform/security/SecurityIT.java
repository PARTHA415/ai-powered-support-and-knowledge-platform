package com.example.aiplatform.security;

import com.example.aiplatform.ai.embedding.EmbeddingService;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises real authentication end to end: real HTTP Basic credentials,
 * real {@link AppUserDetailsService} querying the real {@code app_user}
 * table (seeded by schema.sql - see the Phase 11 docs for the full account
 * list), real BCrypt password verification, real role-based authorization.
 * The controller-level tests elsewhere in this codebase fake the
 * authenticated principal directly (@WithMockUser) to test authorization
 * *rules* cheaply and in isolation; this class is the one place proving the
 * actual login mechanism - the part @WithMockUser deliberately bypasses -
 * genuinely works.
 */
@ActiveProfiles("dev")
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingService embeddingService;

    /**
     * The liveness and readiness probes are reachable without credentials, the
     * same tier as the aggregate health endpoint and the Prometheus scrape.
     *
     * <p>Splitting them from {@code /actuator/health} is what stops a degraded
     * dependency from being read as "restart this pod": readiness failing takes
     * an instance out of rotation, liveness failing kills it, and the aggregate
     * endpoint cannot express the difference. An orchestrator wired to the
     * aggregate will restart a healthy instance because Redis blipped.
     */
    @Test
    void livenessAndReadinessProbesAreReachableWithoutCredentials() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void probesAreDistinctEndpointsFromTheAggregateHealthCheck() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /**
     * Actuator's other endpoints stay unavailable entirely - env and configprops
     * in particular can leak configuration and secrets, and permitting the
     * probes must not widen that.
     */
    @Test
    void otherActuatorEndpointsRemainUnexposed() throws Exception {
        mockMvc.perform(get("/actuator/env").with(httpBasic("dave", "password")))
                .andExpect(status().isNotFound());
    }

    @Test
    void correctCredentialsAuthenticateSuccessfully() throws Exception {
        when(embeddingService.embed(anyString())).thenReturn(new float[1536]);
        when(embeddingService.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[1536]).toList();
        });
        when(embeddingService.modelName()).thenReturn("text-embedding-3-small");

        mockMvc.perform(post("/api/documents")
                        .with(httpBasic("carol", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Doc\",\"content\":\"content\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/documents/search")
                        .with(httpBasic("alice", "not-the-real-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownUsernameIsRejected() throws Exception {
        mockMvc.perform(post("/api/documents/search")
                        .with(httpBasic("not-a-real-user", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"test\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void plainUserRoleIsForbiddenFromIngestingDocuments() throws Exception {
        // alice is a real, correctly-authenticated USER-role account - this
        // is authorization denying a legitimate login, not a login failure.
        mockMvc.perform(post("/api/documents")
                        .with(httpBasic("alice", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Doc\",\"content\":\"content\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportAgentRoleCanIngestDocuments() throws Exception {
        when(embeddingService.embed(anyString())).thenReturn(new float[1536]);
        when(embeddingService.embedAll(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[1536]).toList();
        });
        when(embeddingService.modelName()).thenReturn("text-embedding-3-small");

        mockMvc.perform(post("/api/documents")
                        .with(httpBasic("carol", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Another Doc\",\"content\":\"more content\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void adminRoleCanGenerateRawEmbeddings() throws Exception {
        when(embeddingService.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});

        mockMvc.perform(post("/api/embeddings")
                        .with(httpBasic("dave", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"test\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void supportAgentRoleIsForbiddenFromRawEmbeddings() throws Exception {
        mockMvc.perform(post("/api/embeddings")
                        .with(httpBasic("carol", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"test\"}"))
                .andExpect(status().isForbidden());
    }
}
