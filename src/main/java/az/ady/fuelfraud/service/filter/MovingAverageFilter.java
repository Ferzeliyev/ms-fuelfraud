package az.ady.fuelfraud.service.filter;

import az.ady.fuelfraud.config.FuelFraudProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(2)
@RequiredArgsConstructor
public class MovingAverageFilter implements NoiseFilter {

    private final FuelFraudProperties properties;

    @Override
    public double[] apply(double[] signal) {

        int window = properties.getWindowSize();

        if (window <= 1 || signal == null || signal.length < 3) {
            return signal == null ? new double[0] : signal;
        }

        int size = signal.length;
        int half = window / 2;

        double[] prefix = new double[size + 1];
        for (int i = 0; i < size; i++) {
            prefix[i + 1] = prefix[i] + signal[i];
        }

        double[] result = new double[size];

        for (int i = 0; i < size; i++) {

            int from = Math.max(0, i - half);
            int to = Math.min(size - 1, i + half);

            double sum = prefix[to + 1] - prefix[from];

            result[i] = sum / (to - from + 1);
        }

        return result;
    }
}
