package az.ady.fuelfraud.service;

import java.util.OptionalDouble;

public interface ThresholdCalculator {
    OptionalDouble calculate(double[] deltas);
}
