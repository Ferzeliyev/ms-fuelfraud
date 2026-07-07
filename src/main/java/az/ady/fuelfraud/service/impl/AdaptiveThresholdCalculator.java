package az.ady.fuelfraud.service.impl;

import az.ady.fuelfraud.config.FuelFraudProperties;
import az.ady.fuelfraud.service.ThresholdCalculator;
import az.ady.fuelfraud.util.FuelMathUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.OptionalDouble;

@Component
@RequiredArgsConstructor
public class AdaptiveThresholdCalculator implements ThresholdCalculator {

    private final FuelFraudProperties properties;

    @Override
    public OptionalDouble calculate(double[] deltas) {
        if (deltas == null || deltas.length == 0) {
            return OptionalDouble.empty();
        }

        double[] significantAbsDeltas = Arrays.stream(deltas)
                .map(Math::abs)
                .filter(abs -> abs > properties.getNoiseTolerance())
                .toArray();
        if (significantAbsDeltas.length == 0) {
            return OptionalDouble.empty();
        }

        OptionalDouble gapThreshold = FuelMathUtils.histogramGapThreshold(
                significantAbsDeltas, properties.getHistogramBinCount(), properties.getMinGapFraction());
        if (gapThreshold.isEmpty()) {
            return OptionalDouble.empty();
        }

        double clamped = Math.clamp(gapThreshold.getAsDouble(),
                properties.getMinThreshold(), properties.getMaxThreshold());
        return OptionalDouble.of(clamped);
    }
}
