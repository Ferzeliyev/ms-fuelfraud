package az.ady.fuelfraud.service.filter;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class NoiseFilterChain {
    private final List<NoiseFilter> filters;

    public double[] filter(double[] signal) {
        double[] current = signal;
        for (NoiseFilter noiseFilter : filters) {
            current = noiseFilter.apply(current);
        }
        return current;
    }
}
