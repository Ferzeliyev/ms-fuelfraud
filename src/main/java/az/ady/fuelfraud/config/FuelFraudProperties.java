package az.ady.fuelfraud.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "fuelfraud")
public class FuelFraudProperties {

    @Min(1)
    private int windowSize = 5;

    @Min(1)
    private int medianSize = 5;

    @Positive
    private double minThreshold = 1.0;

    @Positive
    private double maxThreshold = 100.0;

    @PositiveOrZero
    private double noiseTolerance = 0.5;

    @Min(0)
    private int histogramBinCount = 0;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minGapFraction = 0.2;

    @Min(2)
    private int stabilityWindowSize = 15;

    @Positive
    private double startSensitivitySigma = 3.0;

    @Positive
    private double stabilitySigma = 2.0;

    @AssertTrue(message = "minThreshold must not exceed maxThreshold")
    public boolean isThresholdRangeValid() {
        return minThreshold <= maxThreshold;
    }
}
