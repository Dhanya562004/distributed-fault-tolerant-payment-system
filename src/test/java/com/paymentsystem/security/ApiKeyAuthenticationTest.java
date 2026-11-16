package com.paymentsystem.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.controller.RootController;
import com.paymentsystem.entity.ApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticationTest {

    private MockMvc mockMvc;

    @Mock
    private ApiKeyAuthenticationService apiKeyService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @InjectMocks
    private RootController rootController;

    private static final String VALID_KEY = "test_valid_api_key_12345";
    private static final String INVALID_KEY = "test_invalid_api_key_99999";

    @BeforeEach
    void setUp() {
        RequestIdFilter requestIdFilter = new RequestIdFilter();
        ApiKeyAuthenticationFilter authFilter = new ApiKeyAuthenticationFilter(apiKeyService, objectMapper);

        mockMvc = MockMvcBuilders.standaloneSetup(rootController)
                .addFilters(requestIdFilter, authFilter)
                .build();
    }

    @Test
    @DisplayName("Missing API key returns 401 Unauthorized with standardized error format and X-Request-Id header")
    void testMissingApiKey_Returns401() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message", containsString("Authentication required")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Invalid API key returns 403 Forbidden with standardized error format")
    void testInvalidApiKey_Returns403() throws Exception {
        when(apiKeyService.authenticate(INVALID_KEY)).thenReturn(Optional.empty());

        mockMvc.perform(get("/")
                        .header("X-Api-Key", INVALID_KEY))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message", containsString("Access forbidden")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("Valid API key via X-Api-Key header returns 200 OK and propagates X-Request-Id")
    void testValidApiKey_XApiKeyHeader_Returns200() throws Exception {
        ApiKey apiKey = new ApiKey(VALID_KEY, "CUST_001", "Acme Corp");
        when(apiKeyService.authenticate(VALID_KEY)).thenReturn(Optional.of(apiKey));

        mockMvc.perform(get("/")
                        .header("X-Api-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Valid API key via Authorization Bearer header returns 200 OK")
    void testValidApiKey_AuthorizationBearer_Returns200() throws Exception {
        ApiKey apiKey = new ApiKey(VALID_KEY, "CUST_001", "Acme Corp");
        when(apiKeyService.authenticate(VALID_KEY)).thenReturn(Optional.of(apiKey));

        mockMvc.perform(get("/")
                        .header("Authorization", "Bearer " + VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("Supplied X-Request-Id is preserved and echoed in response header and error body")
    void testPreserveSuppliedRequestId() throws Exception {
        String clientRequestId = "req_custom_client_id_42";

        mockMvc.perform(get("/")
                        .header("X-Request-Id", clientRequestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", clientRequestId))
                .andExpect(jsonPath("$.requestId").value(clientRequestId));
    }

    @Test
    @DisplayName("Actuator health endpoint is exempt from authentication")
    void testHealthEndpoint_ExemptFromAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(header().exists("X-Request-Id"));
    }
}
