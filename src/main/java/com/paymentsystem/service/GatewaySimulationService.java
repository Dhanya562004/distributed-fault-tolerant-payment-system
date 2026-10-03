package com.paymentsystem.service;

import com.paymentsystem.dto.request.SimulationConfigRequest;

public interface GatewaySimulationService {
    void updateConfig(SimulationConfigRequest config);
    SimulationConfigRequest getConfig();
    void resetConfig();
    void simulateGatewayCall(String paymentId, String paymentMethod);
    void simulateDbInteraction(String paymentId);
}
