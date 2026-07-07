package az.ady.fuelfraud.benchmark;

import az.ady.fuelfraud.config.FuelFraudProperties;
import az.ady.fuelfraud.dto.internal.DetectionResult;
import az.ady.fuelfraud.dto.internal.MeasurementSeries;
import az.ady.fuelfraud.service.impl.AdaptiveThresholdCalculator;
import az.ady.fuelfraud.service.impl.ConsecutiveDeltaFuelEventDetector;
import az.ady.fuelfraud.service.impl.ExcelExportServiceImpl;
import az.ady.fuelfraud.service.impl.ExcelParsingServiceImpl;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;

public final class DetectionBenchmark {

    private static final int DEFAULT_SAMPLES = 1_000_000;
    private static final long TIME_BUDGET_MS = 5_000;
    private static final long MEMORY_BUDGET_BYTES = 512L * 1024 * 1024;

    private DetectionBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int samples = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_SAMPLES;

        FuelFraudProperties properties = new FuelFraudProperties();
        ConsecutiveDeltaFuelEventDetector detector = new ConsecutiveDeltaFuelEventDetector(
                new AdaptiveThresholdCalculator(properties));

        System.out.printf("Benchmark: %,d samples, budget %,d ms / %,d MB heap (max heap %,d MB)%n",
                samples, TIME_BUDGET_MS, MEMORY_BUDGET_BYTES / (1024 * 1024),
                Runtime.getRuntime().maxMemory() / (1024 * 1024));

        double[] levels = syntheticSeries(samples);

        double[] warmup = syntheticSeries(Math.min(200_000, samples));
        for (int i = 0; i < 3; i++) {
            detector.analyze("warmup", warmup);
        }

        benchmarkDetection(detector, levels);
        benchmarkEndToEnd(detector, levels);
    }

    private static void benchmarkDetection(ConsecutiveDeltaFuelEventDetector detector, double[] levels) {
        System.out.printf("%n--- Detection only (threshold + consecutive-delta detector) ---%n");

        long best = Long.MAX_VALUE;
        DetectionResult result = null;
        for (int run = 1; run <= 3; run++) {
            PeakMemorySampler sampler = new PeakMemorySampler();
            long start = System.nanoTime();
            result = detector.analyze("benchmark", levels);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            long peak = sampler.stop();
            best = Math.min(best, elapsedMs);
            System.out.printf("  run %d: %,6d ms, peak heap %,4d MB%n",
                    run, elapsedMs, peak / (1024 * 1024));
        }

        System.out.printf("  detected: %d event(s), %d refuel(s) / %d theft(s), threshold %.2f L%n",
                result.events().size(), result.refuelCount(), result.theftCount(),
                result.thresholdLiters());
        verdict("detection", best, TIME_BUDGET_MS);
    }

    private static void benchmarkEndToEnd(ConsecutiveDeltaFuelEventDetector detector, double[] levels)
            throws IOException {
        System.out.printf("%n--- End to end (streamed parse -> detect -> streamed export) ---%n");

        File workbook = File.createTempFile("fuelfraud-benchmark", ".xlsx");
        try {
            long written = writeWorkbook(workbook, levels);
            System.out.printf("  input workbook: %,d rows, %,d KB (generation not timed)%n",
                    levels.length, written / 1024);

            ExcelParsingServiceImpl parser = new ExcelParsingServiceImpl();
            ExcelExportServiceImpl exporter = new ExcelExportServiceImpl();
            PeakMemorySampler sampler = new PeakMemorySampler();

            long t0 = System.nanoTime();
            List<MeasurementSeries> sheets = parser.parse(new FileBackedMultipartFile(workbook));
            long parseMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            DetectionResult result = detector.analyze(sheets.get(0).sheetName(), sheets.get(0).levels());
            long detectMs = (System.nanoTime() - t1) / 1_000_000;

            long t2 = System.nanoTime();
            byte[] exported = exporter.export(sheets, List.of(result));
            long exportMs = (System.nanoTime() - t2) / 1_000_000;

            long peak = sampler.stop();
            System.out.printf("  parse  %,6d ms%n  detect %,6d ms%n  export %,6d ms (annotated workbook %,d KB)%n",
                    parseMs, detectMs, exportMs, exported.length / 1024);
            System.out.printf("  peak heap %,d MB (budget %,d MB) -> %s%n",
                    peak / (1024 * 1024), MEMORY_BUDGET_BYTES / (1024 * 1024),
                    peak <= MEMORY_BUDGET_BYTES ? "PASS" : "FAIL");
            verdict("parse + detect", parseMs + detectMs, TIME_BUDGET_MS);
        } finally {
            Files.deleteIfExists(workbook.toPath());
        }
    }

    private static void verdict(String label, long elapsedMs, long budgetMs) {
        System.out.printf("  %s: %,d ms vs %,d ms budget -> %s%n",
                label, elapsedMs, budgetMs, elapsedMs <= budgetMs ? "PASS" : "FAIL");
    }

    private static double[] syntheticSeries(int samples) {
        Random random = new Random(42);
        double[] levels = new double[samples];
        double level = 500.0;

        for (int i = 0; i < samples; i++) {
            int phase = i % 100_000;
            if (phase == 20_000) {
                level += 120.0; 
            } else if (phase >= 50_000 && phase < 50_003) {
                level -= 15.0;  
            } else if (phase >= 80_000 && phase < 80_400) {
                level -= 0.1;   
            }
            level = Math.max(0.0, level);
            levels[i] = level + (random.nextDouble() - 0.5) * 0.6;
        }
        return levels;
    }

    private static long writeWorkbook(File target, double[] levels) throws IOException {
        SXSSFWorkbook workbook = new SXSSFWorkbook(256);
        try (FileOutputStream out = new FileOutputStream(target)) {
            workbook.setCompressTempFiles(true);
            Sheet sheet = workbook.createSheet("benchmark");
            for (int i = 0; i < levels.length; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue(levels[i]);
            }
            workbook.write(out);
        } finally {
            workbook.dispose();
        }
        return target.length();
    }

    private static final class PeakMemorySampler {

        private final Thread thread;
        private volatile boolean running = true;
        private volatile long peakBytes;

        PeakMemorySampler() {
            Runtime runtime = Runtime.getRuntime();
            thread = new Thread(() -> {
                while (running) {
                    peakBytes = Math.max(peakBytes, runtime.totalMemory() - runtime.freeMemory());
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
            }, "peak-memory-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        long stop() {
            running = false;
            thread.interrupt();
            return peakBytes;
        }
    }

    private record FileBackedMultipartFile(File file) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return file.getName();
        }

        @Override
        public String getContentType() {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        @Override
        public boolean isEmpty() {
            return file.length() == 0;
        }

        @Override
        public long getSize() {
            return file.length();
        }

        @Override
        public byte[] getBytes() throws IOException {
            return Files.readAllBytes(file.toPath());
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return new FileInputStream(file);
        }

        @Override
        public void transferTo(File dest) throws IOException {
            Files.copy(file.toPath(), dest.toPath());
        }
    }
}
