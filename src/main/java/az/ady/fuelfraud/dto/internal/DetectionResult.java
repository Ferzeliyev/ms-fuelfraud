package az.ady.fuelfraud.dto.internal;

import az.ady.fuelfraud.enums.FuelEventType;
import az.ady.fuelfraud.util.FuelMathUtils;

import java.util.List;

/**
 * Full outcome of analysing one worksheet: the calculated threshold, the noise
 * characteristics of the signal and the detected events.
 *
 * @param sheetName           worksheet analysed
 * @param measurementCount    number of valid readings in the worksheet
 * @param thresholdLiters     event threshold calculated for this worksheet (litres)
 * @param noiseLevelLiters    robust estimate of the sensor noise sigma (litres)
 * @param noiseMovementCount  sub-threshold direction reversals attributed to noise
 * @param events              detected refuel/theft events, in chronological order
 */
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
