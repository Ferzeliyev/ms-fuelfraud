package az.ady.fuelfraud.service.filter;

public interface NoiseFilter {
    double[] apply(double[] signal);
}
