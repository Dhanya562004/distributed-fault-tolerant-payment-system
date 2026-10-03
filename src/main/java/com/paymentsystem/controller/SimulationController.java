package com.paymentsystem.controller;

import com.paymentsystem.dto.request.SimulationConfigRequest;
import com.paymentsystem.service.GatewaySimulationService;
import com.paymentsystem.worker.DistributedQueueManager;
import com.paymentsystem.worker.EventMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/simulation")
@Tag(name = "Fault Injection & Testing", description = "Endpoints to simulate network latency, timeouts, gateway errors, and inspect DLQ")
public class SimulationController {

    private final GatewaySimulationService simulationService;
    private final DistributedQueueManager queueManager;

    @Autowired
    public SimulationController(GatewaySimulationService simulationService, DistributedQueueManager queueManager) {
        this.simulationService = simulationService;
        this.queueManager = queueManager;
    }

    @PostMapping("/config")
    @Operation(summary = "Update Fault Injection Rules", description = "Enables latency, timeouts, 503 errors, or DB glitches for resilience testing")
    public ResponseEntity<SimulationConfigRequest> updateConfig(@RequestBody SimulationConfigRequest config) {
        simulationService.updateConfig(config);
        return ResponseEntity.ok(simulationService.getConfig());
    }

    @GetMapping("/config")
    @Operation(summary = "Get Current Fault Injection Configuration", description = "Retrieves active fault simulation flags")
    public ResponseEntity<SimulationConfigRequest> getConfig() {
        return ResponseEntity.ok(simulationService.getConfig());
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset Fault Injection Rules", description = "Disables all simulated failures and latency")
    public ResponseEntity<Map<String, String>> resetConfig() {
        simulationService.resetConfig();
        return ResponseEntity.ok(Map.of("message", "All simulation fault rules disabled successfully."));
    }

    @GetMapping("/dlq")
    @Operation(summary = "Inspect Dead Letter Queue (DLQ)", description = "Retrieves unrecoverable failed messages sent to DLQ")
    public ResponseEntity<Map<String, Object>> getDlqMessages() {
        List<EventMessage> messages = queueManager.getDlqMessages();
        Map<String, Object> result = new HashMap<>();
        result.put("dlqCount", queueManager.getDlqCount());
        result.put("messages", messages);
        return ResponseEntity.ok(result);
    }
}
