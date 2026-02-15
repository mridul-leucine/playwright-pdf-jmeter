package com.leucine.pdfdemo;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Benchmark mode: generates N PDFs locally using Playwright, reusing a single
 * browser instance for efficiency. Prints a timing report at the end.
 */
public class LocalBenchmark {

    private static final DateTimeFormatter DIR_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("HHmmss_SSS");

    public static void run(String token, String jobId, int count) throws Exception {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("  LOCAL PDF BENCHMARK (" + count + " PDFs)");
        System.out.println("=".repeat(60));

        String html = LocalPdfRenderer.fetchAndBuildHtml(token, jobId);
        if (html == null) {
            System.out.println("  ERROR: Could not build HTML. Aborting benchmark.");
            return;
        }

        String timestamp = Instant.now().atZone(ZoneId.systemDefault()).format(DIR_FMT);
        Path benchDir = DemoConfig.OUTPUT_DIR.resolve("benchmark_" + timestamp);
        Files.createDirectories(benchDir);

        System.out.println("\n  Launching Playwright browser...");
        long totalStart = System.currentTimeMillis();

        long[] times = new long[count];
        long[] sizes = new long[count];
        boolean[] success = new boolean[count];
        int successCount = 0;
        int failCount = 0;

        try (Playwright pw = Playwright.create()) {
            Browser browser = pw.chromium().launch(
                new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();

            System.out.println("  Browser ready. Starting benchmark...\n");
            printTableHeader();

            for (int i = 0; i < count; i++) {
                long iterStart = System.currentTimeMillis();
                try {
                    byte[] pdf = LocalPdfRenderer.renderPdfBytes(page, html);
                    long iterTime = System.currentTimeMillis() - iterStart;

                    String fileTs = Instant.now().atZone(ZoneId.systemDefault()).format(FILE_FMT);
                    Files.write(benchDir.resolve("report_" + (i + 1) + "_" + fileTs + ".pdf"), pdf);

                    times[i] = iterTime;
                    sizes[i] = pdf.length;
                    success[i] = true;
                    successCount++;

                    System.out.printf("  \u2502 %7d \u2502 %6s \u2502 %8.1f \u2502 %8d \u2502%n",
                        i + 1, "OK", pdf.length / 1024.0, iterTime);
                } catch (Exception e) {
                    long iterTime = System.currentTimeMillis() - iterStart;
                    times[i] = iterTime;
                    success[i] = false;
                    failCount++;

                    System.out.printf("  \u2502 %7d \u2502 %6s \u2502 %8s \u2502 %8d \u2502%n",
                        i + 1, "FAIL", "-", iterTime);
                }
            }

            printTableFooter();
            context.close();
            browser.close();
        }

        long totalTime = System.currentTimeMillis() - totalStart;
        printSummary(count, successCount, failCount, times, sizes, success, totalTime, benchDir);
    }

    private static void printTableHeader() {
        System.out.println("  \u250c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u252c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2510");
        System.out.printf("  \u2502 %7s \u2502 %6s \u2502 %8s \u2502 %8s \u2502%n", "PDF", "Status", "Size(KB)", "Time(ms)");
        System.out.println("  \u251c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u253c\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2524");
    }

    private static void printTableFooter() {
        System.out.println("  \u2514\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2534\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2518");
    }

    private static void printSummary(int count, int successCount, int failCount,
                                      long[] times, long[] sizes, boolean[] success,
                                      long totalTime, Path benchDir) {
        long minTime = Long.MAX_VALUE, maxTime = 0, sumTime = 0, totalSize = 0;
        for (int i = 0; i < count; i++) {
            if (success[i]) {
                sumTime += times[i];
                totalSize += sizes[i];
                if (times[i] < minTime) minTime = times[i];
                if (times[i] > maxTime) maxTime = times[i];
            }
        }

        long avgTime = successCount > 0 ? sumTime / successCount : 0;
        double throughput = totalTime > 0 ? (successCount * 1000.0 / totalTime) : 0;

        System.out.println("\n  Summary:");
        System.out.printf("    Total PDFs:        %d%n", count);
        System.out.printf("    Successful:        %d%n", successCount);
        System.out.printf("    Failed:            %d%n", failCount);
        System.out.printf("    Total time:        %,dms%n", totalTime);
        System.out.printf("    Avg per PDF:       %,dms%n", avgTime);
        if (successCount > 0) {
            System.out.printf("    Min:               %,dms%n", minTime);
            System.out.printf("    Max:               %,dms  (first run \u2014 cold start)%n", maxTime);
        }
        System.out.printf("    Throughput:        %.1f PDFs/sec%n", throughput);
        System.out.printf("    Total size:        %.1f MB%n", totalSize / (1024.0 * 1024.0));
        System.out.printf("    Output:            %s%n", benchDir.toAbsolutePath());
    }
}
