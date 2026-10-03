package com.paymentsystem.service.impl;

import com.paymentsystem.dto.request.SimulationConfigRequest;
import com.paymentsystem.exception.PaymentProcessingException;
import com.paymentsystem.service.GatewaySimulationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Random;

@Service
public class GatewaySimulationServiceImpl implements GatewaySimulationService {

    private static final Logger logger = LoggerFactory.getLogger(GatewaySimulationServiceImpl.class);
    private final Random random = new Random();

    private boolean enableLatency = false;
    private long latencyMs = 1500;

    private boolean enableTimeouts = false;
    private double timeoutProbability = 0.25;

    private boolean enableGatewayFailures = false;
    private double failureProbability = 0.35;

    private boolean enablePartialDbFailures = false;
    private double dbFailureProbability = 0.20;

    @Override
    public synchronized void updateConfig(SimulationConfigRequest config) {
        this.enableLatency = config.isEnableLatency();
        this.latencyMs = config.getLatencyMs();
        this.enableTimeouts = config.isEnableTimeouts();
        this.timeoutProbability = config.getTimeoutProbability();
        this.enableGatewayFailures = config.isEnableGatewayFailures();
        this.failureProbability = config.getFailureProbability();
        this.enablePartialDbFailures = config.isEnablePartialDbFailures();
        this.dbFailureProbability = config.getDbFailureProbability();

        logger.info("Updated Fault Simulation Config: latency={} ({}ms), timeouts={} ({}), gatewayFailures={} ({}), dbFailures={} ({})",
                enableLatency, latencyMs, enableTimeouts, timeoutProbability, enableGatewayFailures, failureProbability, enablePartialDbFailures, dbFailureProbability);
    }

    @Override
    public synchronized SimulationConfigRequest getConfig() {
        SimulationConfigRequest config = new SimulationConfigRequest();
        config.setEnableLatency(enableLatency);
        config.setLatencyMs(latencyMs);
        config.setEnableTimeouts(enableTimeouts);
        config.setTimeoutProbability(timeoutProbability);
        config.setEnableGatewayFailures(enableGatewayFailures);
        config.setFailureProbability(failureProbability);
        config.setEnablePartialDbFailures(enablePartialDbFailures);
        config.setDbFailureProbability(dbFailureProbability);
        return config;
    }

    @Override
    public synchronized void resetConfig() {
        this.enableLatency = false;
        this.enableTimeouts = false;
        this.enableGatewayFailures = false;
        this.enablePartialDbFailures = false;
        logger.info("Reset Fault Simulation Config to clean default (all disabled)");
    }

    @Override
    public void simulateGatewayCall(String paymentId, String paymentMethod) {
        if (enableLatency) {
            try {
                logger.info("[SIMULATION] Injecting network latency of {}ms for payment: {}", latencyMs, paymentId);
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (enableTimeouts && random.nextDouble() < timeoutProbability) {
            logger.warn("[SIMULATION] Injected Gateway Socket Timeout for payment: {}", paymentId);
            throw new PaymentProcessingException("Gateway Socket Timeout: Read timed out contacting payment gateway endpoint");
        }

        if (enableGatewayFailures && random.nextDouble() < failureProbability) {
            logger.warn("[SIMULATION] Injected Payment Gateway 503 Error for payment: {}", paymentId);
            throw new PaymentProcessingException("Gateway Error: 503 Service Unavailable (Upstream Issuer Declined)");
        }
    }

    @Override
    public void simulateDbInteraction(String paymentId) {
        if (enablePartialDbFailures && random.nextDouble() < dbFailureProbability) {
            logger.warn("[SIMULATION] Injected Partial DB Failure for payment: {}", paymentId);
            throw new PaymentProcessingException("Partial DB Failure: Connection lock acquisition timeout on transaction record");
        }
    }
}
