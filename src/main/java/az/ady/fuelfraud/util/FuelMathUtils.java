package az.ady.fuelfraud.util;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Arrays;
import java.util.OptionalDouble;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FuelMathUtils {

    private static final double MAD_TO_SIGMA = 1.4826;

    private static final int MIN_AUTO_BINS = 10;

    /**
     * Rounds to 2 decimals — the precision written into the exported workbook's
     * threshold cell (G1), so every layer classifies identically.
     */
    public static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Consecutive-row differences: {@code deltas[i] = levels[i + 1] - levels[i]}. */
    public static double[] deltas(double[] levels) {
        double[] deltas = new double[levels.length - 1];
        for (int i = 1; i < levels.length; i++) {
            deltas[i - 1] = levels[i] - levels[i - 1];
        }
        return deltas;
    }

    public static double median(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return (sorted.length % 2 == 0)
                ? (sorted[mid - 1] + sorted[mid]) / 2.0
                : sorted[mid];
    }

    public static double robustNoiseSigma(double[] deltas) {
        if (deltas == null || deltas.length == 0) {
            return 0.0;
        }
        double medianDelta = median(deltas);
        double[] absDeviations = new double[deltas.length];
        for (int i = 0; i < deltas.length; i++) {
            absDeviations[i] = Math.abs(deltas[i] - medianDelta);
        }
        return MAD_TO_SIGMA * median(absDeviations);
    }

    public static OptionalDouble histogramGapThreshold(double[] absDeltas, int binCountConfig,
                                                       double minGapFraction) {
        if (absDeltas == null || absDeltas.length == 0) {
            return OptionalDouble.empty();
        }
        double max = Arrays.stream(absDeltas).max().orElse(0.0);
        if (max <= 0.0) {
            return OptionalDouble.empty();
        }

        int bins = binCountConfig > 0
                ? binCountConfig
                : Math.max(MIN_AUTO_BINS, (int) Math.ceil(Math.sqrt(absDeltas.length)));
        double binWidth = max / bins;

        int[] histogram = new int[bins];
        for (double value : absDeltas) {
            int bin = Math.min(bins - 1, (int) (value / binWidth));
            histogram[bin]++;
        }

        int[] gap = largestEmptyRunBelowOccupied(histogram);
        if (gap == null || (double) gap[1] / bins < minGapFraction) {
            return OptionalDouble.empty();
        }

        double thresholdCenter = (gap[0] + gap[1] / 2.0) * binWidth;
        return OptionalDouble.of(thresholdCenter);
    }

    /**
     * Largest run of empty bins that is followed by an occupied bin. The run may
     * start at bin 0: sub-tolerance noise deltas were filtered out before binning,
     * so the noise cluster conceptually sits below the histogram range and a
     * leading empty run is a genuine noise/event gap (e.g. a sheet whose only
     * significant delta is one large theft). A trailing run — nothing occupied
     * above it — is never a gap.
     */
    private static int[] largestEmptyRunBelowOccupied(int[] histogram) {
        int bestStart = -1;
        int bestLength = 0;

        int runStart = -1;

        for (int i = 0; i < histogram.length; i++) {
            if (histogram[i] == 0) {
                if (runStart < 0) {
                    runStart = i;
                }
            } else {
                if (runStart >= 0) {
                    int length = i - runStart;
                    if (length > bestLength) {
                        bestLength = length;
                        bestStart = runStart;
                    }
                }
                runStart = -1;
            }
        }
        return bestLength > 0 ? new int[]{bestStart, bestLength} : null;
    }
}
