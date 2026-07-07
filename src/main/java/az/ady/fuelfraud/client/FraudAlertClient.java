package az.ady.fuelfraud.client;

import az.ady.fuelfraud.dto.internal.DetectedEvent;

public interface FraudAlertClient {
    void sendTheftAlert(DetectedEvent event);
}
