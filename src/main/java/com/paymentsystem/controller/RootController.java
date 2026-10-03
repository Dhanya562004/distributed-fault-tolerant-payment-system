package com.paymentsystem.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@Tag(name = "Root Index", description = "Root welcome endpoint providing API directory and quick links")
public class RootController {

    @GetMapping("/")
    @Operation(summary = "Root Welcome Endpoint", description = "Provides system status, documentation URL, and API directory")
    public ResponseEntity<Map<String, Object>> getRootInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("system", "Distributed Fault-Tolerant Payment System");
        info.put("status", "UP");
        info.put("version", "1.0.0");
        info.put("swaggerDocumentation", "/swagger-ui.html");
        info.put("apiDocs", "/v3/api-docs");
        info.put("healthCheck", "/actuator/health");
        info.put("metricsEndpoint", "/api/v1/metrics");
        info.put("paymentsEndpoint", "/api/v1/payments");
        info.put("refundsEndpoint", "/api/v1/refunds");
        info.put("simulationEndpoint", "/api/v1/simulation/config");
        return ResponseEntity.ok(info);
    }
}
