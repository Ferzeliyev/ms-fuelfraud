package az.ady.fuelfraud.client;

import az.ady.fuelfraud.dto.internal.DetectedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LoggingFraudAlertClient implements FraudAlertClient {

    @Override
    public void sendTheftAlert(DetectedEvent event) {
        log.warn("FUEL THEFT ALERT | sheet='{}' | series index {}-{} | stolen={} L | startFuel={} L | endFuel={} L | confidence={}",
                event.sheetName(), event.startIndex(), event.endIndex(), event.volumeLiters(),
                event.startFuel(), event.endFuel(), event.confidenceScore());
    }
}
