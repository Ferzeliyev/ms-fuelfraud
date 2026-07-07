package az.ady.fuelfraud.dto.internal;

/**
 * All validated fuel-sensor measurements of one worksheet, stored as primitive
 * arrays so multi-million-sample series carry no per-measurement object overhead.
 *
 * <p>Both arrays have the same length and are index-aligned: {@code levels[i]} was
 * read from 1-based Excel row {@code rowNumbers[i]} (kept for traceability back to
 * the source file). The arrays are never mutated after construction.</p>
 *
 * @param sheetName  the worksheet the series was read from
 * @param rowNumbers 1-based Excel row numbers, in chronological (row) order
 * @param levels     fuel levels in litres; always finite and non-negative
 */
public record MeasurementSeries(
        String sheetName,
        int[] rowNumbers,
        double[] levels
) {

    /** Number of measurements in the series. */
    public int size() {
        return levels.length;
    }

    /**
     * 1-based Excel row of the measurement at {@code seriesIndex}; falls back to
     * {@code seriesIndex + 1} for out-of-range indexes.
     */
    public int rowNumberAt(int seriesIndex) {
        return seriesIndex >= 0 && seriesIndex < levels.length
                ? rowNumbers[seriesIndex]
                : seriesIndex + 1;
    }
}
