package az.ady.fuelfraud.service.impl;

import az.ady.fuelfraud.dto.internal.DetectedEvent;
import az.ady.fuelfraud.dto.internal.DetectionResult;
import az.ady.fuelfraud.enums.FuelEventType;
import az.ady.fuelfraud.service.FuelEventDetector;
import az.ady.fuelfraud.service.ThresholdCalculator;
import az.ady.fuelfraud.util.FuelMathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class ConsecutiveDeltaFuelEventDetector implements FuelEventDetector {

    private final ThresholdCalculator thresholdCalculator;

    @Override
    public DetectionResult analyze(String sheetName, double[] fuelLevels) {
        int count = fuelLevels == null ? 0 : fuelLevels.length;
        if (count < 2) {
            return new DetectionResult(sheetName, count, 0.0, 0.0, 0, List.of());
        }

        double[] deltas = FuelMathUtils.deltas(fuelLevels);
        double noiseSigma = FuelMathUtils.robustNoiseSigma(deltas);

        OptionalDouble calculated = thresholdCalculator.calculate(deltas);
        if (calculated.isEmpty()) {
            int noiseMovements = countNonZero(deltas);
            log.debug("Worksheet '{}': no noise/event separation found — {} sample(s) treated as noise only",
                    sheetName, count);
            return new DetectionResult(sheetName, count, 0.0, FuelMathUtils.round2(noiseSigma),
                    noiseMovements, List.of());
        }


        double threshold = FuelMathUtils.round2(calculated.getAsDouble());
        List<DetectedEvent> events = detectEvents(sheetName, fuelLevels, deltas, threshold);
        int noiseMovements = countSubThreshold(deltas, threshold);

        log.debug("Worksheet '{}': {} sample(s), threshold={} L (adaptive), sigma={} L, {} event(s), {} noise movement(s)",
                sheetName, count, threshold, FuelMathUtils.round2(noiseSigma), events.size(), noiseMovements);
        return new DetectionResult(sheetName, count, threshold, FuelMathUtils.round2(noiseSigma),
                noiseMovements, events);
    }


    private List<DetectedEvent> detectEvents(String sheetName, double[] levels,
                                             double[] deltas, double threshold) {
        List<DetectedEvent> events = new ArrayList<>();
        for (int i = 0; i < deltas.length; i++) {
            int direction = classify(deltas[i], threshold);
            if (direction == 0) {
                continue;
            }
            events.add(buildEvent(sheetName, levels, i, i + 1, threshold,
                    direction > 0 ? FuelEventType.REFUEL : FuelEventType.THEFT));
        }
        return events;
    }

    /** @return {@code +1} refuel direction, {@code -1} theft direction, {@code 0} noise (ignored). */
    private int classify(double delta, double threshold) {
        if (delta > threshold) {
            return 1;
        }
        if (delta < -threshold) {
            return -1;
        }
        return 0;
    }

    private DetectedEvent buildEvent(String sheetName, double[] levels, int startIndex, int endIndex,
                                     double threshold, FuelEventType type) {
        double startFuel = levels[startIndex];
        double endFuel = levels[endIndex];
        double difference = endFuel - startFuel;
        return new DetectedEvent(sheetName, type, startIndex, endIndex,
                FuelMathUtils.round2(startFuel), FuelMathUtils.round2(endFuel),
                FuelMathUtils.round2(difference), FuelMathUtils.round2(threshold),
                confidenceScore(Math.abs(difference), threshold));
    }


    private double confidenceScore(double volume, double threshold) {
        if (threshold <= 0.0) {
            return 1.0;
        }
        return FuelMathUtils.round2(Math.clamp(volume / (2.0 * threshold), 0.0, 1.0));
    }

    private int countNonZero(double[] deltas) {
        int count = 0;
        for (double delta : deltas) {
            if (delta != 0.0) {
                count++;
            }
        }
        return count;
    }

    private int countSubThreshold(double[] deltas, double threshold) {
        int count = 0;
        for (double delta : deltas) {
            if (delta != 0.0 && Math.abs(delta) <= threshold) {
                count++;
            }
        }
        return count;
    }
}
