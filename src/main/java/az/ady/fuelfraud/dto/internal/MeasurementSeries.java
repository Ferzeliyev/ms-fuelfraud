package az.ady.fuelfraud.dto.internal;

public record MeasurementSeries(
        String sheetName,
        int[] rowNumbers,
        double[] levels
) {

    public int size() {
        return levels.length;
    }

    public int rowNumberAt(int seriesIndex) {
        return seriesIndex >= 0 && seriesIndex < levels.length
                ? rowNumbers[seriesIndex]
                : seriesIndex + 1;
    }
}
