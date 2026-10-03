package com.paymentsystem.controller;

import com.paymentsystem.dto.response.AuditLogResponse;
import com.paymentsystem.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
@Tag(name = "Audit Trail", description = "Endpoints for inspecting immutable document audit logs")
public class AuditController {

    private final AuditService auditService;

    @Autowired
    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/logs")
    @Operation(summary = "Get All Audit Logs", description = "Fetches recent state change audit documents")
    public ResponseEntity<List<AuditLogResponse>> getAllLogs() {
        return ResponseEntity.ok(auditService.getAllLogs());
    }

    @GetMapping("/payments/{paymentId}")
    @Operation(summary = "Get Payment Audit Logs", description = "Fetches complete state transition audit timeline for specific payment")
    public ResponseEntity<List<AuditLogResponse>> getLogsForPayment(@PathVariable String paymentId) {
        return ResponseEntity.ok(auditService.getLogsForPayment(paymentId));
    }
}
