package az.ady.fuelfraud.dto.internal;

import az.ady.fuelfraud.enums.FuelEventType;
import az.ady.fuelfraud.util.FuelMathUtils;

import java.util.List;

public record DetectionResult(
        String sheetName,
        int measurementCount,
        double thresholdLiters,
        double noiseLevelLiters,
        int noiseMovementCount,
        List<DetectedEvent> events
) {

    public double totalStolenLiters() {
        return FuelMathUtils.round2(sumOf(FuelEventType.THEFT));
    }

    public double totalRefueledLiters() {
        return FuelMathUtils.round2(sumOf(FuelEventType.REFUEL));
    }

    public int theftCount() {
        return countOf(FuelEventType.THEFT);
    }

    public int refuelCount() {
        return countOf(FuelEventType.REFUEL);
    }

    private double sumOf(FuelEventType type) {
        return events.stream().filter(e -> e.type() == type).mapToDouble(DetectedEvent::volumeLiters).sum();
    }

    private int countOf(FuelEventType type) {
        return (int) events.stream().filter(e -> e.type() == type).count();
    }
}
