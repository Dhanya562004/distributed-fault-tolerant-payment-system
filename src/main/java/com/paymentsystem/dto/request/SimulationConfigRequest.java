package com.paymentsystem.dto.request;

public class SimulationConfigRequest {

    private boolean enableLatency = false;
    private long latencyMs = 2000;

    private boolean enableTimeouts = false;
    private double timeoutProbability = 0.3;

    private boolean enableGatewayFailures = false;
    private double failureProbability = 0.4;

    private boolean enablePartialDbFailures = false;
    private double dbFailureProbability = 0.2;

    public SimulationConfigRequest() {
    }

    public boolean isEnableLatency() {
        return enableLatency;
    }

    public void setEnableLatency(boolean enableLatency) {
        this.enableLatency = enableLatency;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public boolean isEnableTimeouts() {
        return enableTimeouts;
    }

    public void setEnableTimeouts(boolean enableTimeouts) {
        this.enableTimeouts = enableTimeouts;
    }

    public double getTimeoutProbability() {
        return timeoutProbability;
    }

    public void setTimeoutProbability(double timeoutProbability) {
        this.timeoutProbability = timeoutProbability;
    }

    public boolean isEnableGatewayFailures() {
        return enableGatewayFailures;
    }

    public void setEnableGatewayFailures(boolean enableGatewayFailures) {
        this.enableGatewayFailures = enableGatewayFailures;
    }

    public double getFailureProbability() {
        return failureProbability;
    }

    public void setFailureProbability(double failureProbability) {
        this.failureProbability = failureProbability;
    }

    public boolean isEnablePartialDbFailures() {
        return enablePartialDbFailures;
    }

    public void setEnablePartialDbFailures(boolean enablePartialDbFailures) {
        this.enablePartialDbFailures = enablePartialDbFailures;
    }

    public double getDbFailureProbability() {
        return dbFailureProbability;
    }

    public void setDbFailureProbability(double dbFailureProbability) {
        this.dbFailureProbability = dbFailureProbability;
    }
}
