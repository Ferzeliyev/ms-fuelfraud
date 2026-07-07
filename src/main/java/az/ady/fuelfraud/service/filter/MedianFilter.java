package az.ady.fuelfraud.service.filter;

import az.ady.fuelfraud.config.FuelFraudProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;

@Component
@Order(1)
@RequiredArgsConstructor
public class MedianFilter implements NoiseFilter {

    private final FuelFraudProperties properties;

    @Override
    public double[] apply(double[] signal) {

        int window = properties.getMedianSize();

        if (window <= 1 || signal == null || signal.length < 3) {
            return signal == null ? new double[0] : signal;
        }

        int size = signal.length;
        int half = window / 2;

        double[] result = new double[size];
        // The centred slice spans up to 2*half+1 samples, which exceeds `window`
        // by one when the configured window is even.
        double[] scratch = new double[2 * half + 1];

        for (int i = 0; i < size; i++) {

            int from = Math.max(0, i - half);
            int to = Math.min(size - 1, i + half);

            int length = to - from + 1;

            System.arraycopy(signal, from, scratch, 0, length);

            Arrays.sort(scratch, 0, length);

            if (length % 2 == 0) {
                result[i] = (scratch[length / 2 - 1] + scratch[length / 2]) / 2.0;
            } else {
                result[i] = scratch[length / 2];
            }
        }

        return result;
    }
}
