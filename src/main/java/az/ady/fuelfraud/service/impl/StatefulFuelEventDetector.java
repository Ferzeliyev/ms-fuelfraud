package az.ady.fuelfraud.service.impl;

import az.ady.fuelfraud.config.FuelFraudProperties;
import az.ady.fuelfraud.dto.internal.DetectedEvent;
import az.ady.fuelfraud.dto.internal.DetectionResult;
import az.ady.fuelfraud.enums.FuelEventType;
import az.ady.fuelfraud.service.FuelEventDetector;
import az.ady.fuelfraud.service.ThresholdCalculator;
import az.ady.fuelfraud.service.filter.NoiseFilterChain;
import az.ady.fuelfraud.util.FuelMathUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatefulFuelEventDetector implements FuelEventDetector {

    private static final double FALLBACK_VOLUME_SIGMA = 4.0;

    private enum State { IDLE, EVENT_STARTED, TRACKING, STABLE, FINISHED }

    private final NoiseFilterChain noiseFilterChain;
    private final ThresholdCalculator thresholdCalculator;
    private final FuelFraudProperties properties;

    @Override
    public DetectionResult analyze(String sheetName, double[] fuelLevels) {
        int count = fuelLevels == null ? 0 : fuelLevels.length;
        if (count < 2) {
            return new DetectionResult(sheetName, count, 0.0, 0.0, 0, List.of());
        }

        double[] filtered = noiseFilterChain.filter(fuelLevels);
        double[] rawDeltas = FuelMathUtils.deltas(fuelLevels);
        double[] filteredDeltas = FuelMathUtils.deltas(filtered);
        double noiseSigma = FuelMathUtils.robustNoiseSigma(rawDeltas);

        double startTolerance = Math.max(properties.getNoiseTolerance(),
                properties.getStartSensitivitySigma() * noiseSigma);
        double stabilityTolerance = Math.max(properties.getNoiseTolerance(),
                properties.getStabilitySigma() * noiseSigma);
        double minVolume = minEventVolume(filteredDeltas, noiseSigma);

        List<DetectedEvent> events =
                runStateMachine(sheetName, filtered, startTolerance, stabilityTolerance, minVolume);
        int noiseMovements = countSubThreshold(rawDeltas, minVolume);

        log.debug("Worksheet '{}': {} sample(s), minVolume={} L, sigma={} L, startTol={} L, "
                        + "stabilityTol={} L, {} event(s), {} noise movement(s)",
                sheetName, count, round(minVolume), round(noiseSigma), round(startTolerance),
                round(stabilityTolerance), events.size(), noiseMovements);
        return new DetectionResult(sheetName, count, round(minVolume), round(noiseSigma),
                noiseMovements, events);
    }

    private double minEventVolume(double[] filteredDeltas, double noiseSigma) {
        double volume = thresholdCalculator.calculate(filteredDeltas)
                .orElse(FALLBACK_VOLUME_SIGMA * noiseSigma);
        return Math.clamp(volume, properties.getMinThreshold(), properties.getMaxThreshold());
    }

    private List<DetectedEvent> runStateMachine(String sheetName, double[] levels,
                                                double startTolerance, double stabilityTolerance,
                                                double minVolume) {
        int window = properties.getStabilityWindowSize();
        List<DetectedEvent> events = new ArrayList<>();

        State state = State.IDLE;
        double baseline = levels[0];
        int baselineIndex = 0;
        double anchor = levels[0];
        int anchorIndex = 0;
        int eventStart = 0;
        double postLevel = 0.0;

        for (int i = 1; i < levels.length; i++) {
            boolean consumed = false;
            while (!consumed) {
                switch (state) {
                    case IDLE -> {
                        if (isStable(levels, i, window, stabilityTolerance)) {
                            baseline = windowMean(levels, i, window);
                            baselineIndex = i;
                            if (Math.abs(baseline - anchor) >= minVolume) {
                                events.add(buildEvent(sheetName, anchor, baseline,
                                        anchorIndex, i, minVolume));
                                anchor = baseline;
                                anchorIndex = i;
                            }
                        }
                        if (Math.abs(levels[i] - baseline) > startTolerance) {
                            state = State.EVENT_STARTED;
                            eventStart = baselineIndex;
                        }
                        consumed = true;
                    }
                    case EVENT_STARTED -> {
                        state = Math.abs(levels[i] - baseline) > startTolerance
                                ? State.TRACKING
                                : State.IDLE;
                        consumed = true;
                    }
                    case TRACKING -> {
                        if (isSettled(levels, i, window, stabilityTolerance)) {
                            postLevel = windowMean(levels, i, window);
                            state = State.STABLE;
                        } else {
                            consumed = true;
                        }
                    }
                    case STABLE -> {
                        state = Math.abs(postLevel - baseline) >= minVolume
                                ? State.FINISHED
                                : State.IDLE;
                        if (state == State.IDLE) {
                            baseline = postLevel;
                            baselineIndex = i;
                            consumed = true;
                        }
                    }
                    case FINISHED -> {
                        events.add(buildEvent(sheetName, baseline, postLevel, eventStart,
                                Math.max(eventStart + 1, i - window + 1), minVolume));
                        baseline = postLevel;
                        baselineIndex = i;
                        anchor = postLevel;
                        anchorIndex = i;
                        state = State.IDLE;
                        consumed = true;
                    }
                }
            }
        }

        finalizeTailEvent(sheetName, levels, state, baseline, eventStart, minVolume, window, events);
        return events;
    }

    private void finalizeTailEvent(String sheetName, double[] levels, State state,
                                   double baseline, int eventStart, double minVolume,
                                   int window, List<DetectedEvent> events) {
        if (state != State.TRACKING) {
            return;
        }
        int last = levels.length - 1;
        double tailLevel = windowMean(levels, last, Math.min(window, last - eventStart));
        if (Math.abs(tailLevel - baseline) >= minVolume) {
            events.add(buildEvent(sheetName, baseline, tailLevel, eventStart, last, minVolume));
        }
    }

    private DetectedEvent buildEvent(String sheetName, double startFuel, double endFuel,
                                     int startIndex, int endIndex, double minVolume) {
        double difference = endFuel - startFuel;
        return new DetectedEvent(sheetName,
                difference > 0 ? FuelEventType.REFUEL : FuelEventType.THEFT,
                startIndex, endIndex,
                round(startFuel), round(endFuel), round(difference), round(minVolume),
                confidenceScore(Math.abs(difference), minVolume));
    }

    private boolean isSettled(double[] levels, int i, int window, double tolerance) {
        if (i + 1 < 2 * window || !isStable(levels, i, window, tolerance)) {
            return false;
        }
        double currentMean = windowMean(levels, i, window);
        double previousMean = windowMean(levels, i - window, window);
        return Math.abs(currentMean - previousMean) <= tolerance / 2.0;
    }

    private boolean isStable(double[] levels, int i, int window, double tolerance) {
        if (i + 1 < window) {
            return false;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int k = i - window + 1; k <= i; k++) {
            min = Math.min(min, levels[k]);
            max = Math.max(max, levels[k]);
        }
        return max - min <= tolerance;
    }

    private double windowMean(double[] levels, int i, int window) {
        int effective = Math.max(1, Math.min(window, i + 1));
        double sum = 0.0;
        for (int k = i - effective + 1; k <= i; k++) {
            sum += levels[k];
        }
        return sum / effective;
    }

    private double confidenceScore(double volume, double minVolume) {
        if (minVolume <= 0.0) {
            return 1.0;
        }
        return round(Math.clamp(volume / (2.0 * minVolume), 0.0, 1.0));
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

    private static double round(double value) {
        return FuelMathUtils.round2(value);
    }
}
