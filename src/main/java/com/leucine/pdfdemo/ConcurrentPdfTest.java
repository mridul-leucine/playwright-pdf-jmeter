package com.leucine.pdfdemo;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ConcurrentPdfTest {

    public static void run(String token, String jobId, int n) throws Exception {
        System.out.printf("%n%s%n", "=".repeat(60));
        System.out.printf("  CONCURRENT PDF TEST — %d simultaneous requests%n", n);
        System.out.printf("%s%n", "=".repeat(60));

        // Pre-fetch job info
        System.out.printf("%n  Fetching job %s info...%n", jobId);
        JsonNode response = ApiClient.apiGet(DemoConfig.STREEM_API + "/jobs/" + jobId, token);
        if (response == null) {
            System.out.println("  ERROR: Could not fetch job data. Check token/jobId.");
            return;
        }

        String jobCode = response.path("data").path("code").asText(jobId);
        String state = response.path("data").path("state").asText("?");
        System.out.printf("  Job: %s | State: %s%n", jobCode, state);

        String printUrl = DemoConfig.STREEM_API + "/jobs/" + jobId + "/print";
        Files.createDirectories(DemoConfig.OUTPUT_DIR);

        System.out.printf("%n  Firing %d concurrent requests...%n%n", n);

        // Table header
        System.out.println("  ┌─────────┬────────┬──────────┬──────────┐");
        System.out.println("  │ Request │ Status │ Size(KB) │ Time(ms) │");
        System.out.println("  ├─────────┼────────┼──────────┼──────────┤");

        long wallStart = System.currentTimeMillis();
        ExecutorService executor = Executors.newFixedThreadPool(n);
        AtomicInteger okCount = new AtomicInteger(0);
        List<CompletableFuture<long[]>> futures = new ArrayList<>();

        for (int i = 1; i <= n; i++) {
            final int reqId = i;
            futures.add(CompletableFuture.supplyAsync(() -> {
                long start = System.currentTimeMillis();
                try {
                    byte[] pdf = ApiClient.downloadPdf(printUrl, token);
                    long elapsed = System.currentTimeMillis() - start;

                    if (pdf != null && pdf.length > 0) {
                        Path path = DemoConfig.OUTPUT_DIR.resolve(
                            "concurrent_" + reqId + "_" + jobCode + ".pdf");
                        Files.write(path, pdf);
                        okCount.incrementAndGet();
                        printRow(reqId, "OK", pdf.length / 1024.0, elapsed);
                        return new long[]{1, elapsed, pdf.length};
                    } else {
                        printRow(reqId, "FAIL", 0, elapsed);
                        return new long[]{0, elapsed, 0};
                    }
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - start;
                    printRow(reqId, "ERR", 0, elapsed);
                    return new long[]{0, elapsed, 0};
                }
            }, executor));
        }

        // Collect results
        long sumMs = 0;
        long minMs = Long.MAX_VALUE;
        long maxMs = 0;
        for (CompletableFuture<long[]> f : futures) {
            long[] result = f.join();
            sumMs += result[1];
            minMs = Math.min(minMs, result[1]);
            maxMs = Math.max(maxMs, result[1]);
        }
        executor.shutdown();

        long wallElapsed = System.currentTimeMillis() - wallStart;
        int ok = okCount.get();
        int fail = n - ok;

        // Table footer
        System.out.println("  └─────────┴────────┴──────────┴──────────┘");

        // Summary
        System.out.printf("%n  Summary:%n");
        System.out.printf("    Total requests:    %d%n", n);
        System.out.printf("    Successful:        %d%n", ok);
        System.out.printf("    Failed:            %d%n", fail);
        System.out.printf("    Wall clock time:   %,dms%n", wallElapsed);
        System.out.printf("    Avg per request:   %,dms%n", sumMs / n);
        System.out.printf("    Min:               %,dms%n", minMs);
        System.out.printf("    Max:               %,dms%n", maxMs);
        if (wallElapsed > 0) {
            System.out.printf("    Throughput:        %.1f PDFs/sec%n", ok / (wallElapsed / 1000.0));
        }
        System.out.printf("%n  PDFs saved to: %s%n", DemoConfig.OUTPUT_DIR.toAbsolutePath());
    }

    private static synchronized void printRow(int reqId, String status, double sizeKb, long timeMs) {
        System.out.printf("  │ %7d │ %6s │ %8.1f │ %8d │%n", reqId, status, sizeKb, timeMs);
    }

    public static void runSingle(String token, String jobId) throws Exception {
        System.out.printf("%n%s%n", "=".repeat(60));
        System.out.printf("  SINGLE PDF GENERATION TEST%n");
        System.out.printf("%s%n", "=".repeat(60));

        // Fetch job info
        System.out.printf("%n  Fetching job %s...%n", jobId);
        JsonNode response = ApiClient.apiGet(DemoConfig.STREEM_API + "/jobs/" + jobId, token);
        if (response == null) {
            System.out.println("  ERROR: Could not fetch job data.");
            return;
        }

        JsonNode jobData = response.get("data");
        String jobCode = jobData.path("code").asText(jobId);
        String state = jobData.path("state").asText("?");

        // Count parameters
        int totalParams = 0;
        JsonNode stages = jobData.path("checklist").path("stages");
        if (stages.isArray()) {
            for (JsonNode stage : stages) {
                JsonNode tasks = stage.path("tasks");
                if (tasks.isArray()) {
                    for (JsonNode task : tasks) {
                        totalParams += task.path("parameters").size();
                    }
                }
            }
        }

        System.out.printf("  Job: %s | State: %s | Parameters: %d%n", jobCode, state, totalParams);

        // Generate PDF
        String printUrl = DemoConfig.STREEM_API + "/jobs/" + jobId + "/print";
        Files.createDirectories(DemoConfig.OUTPUT_DIR);

        System.out.printf("%n  Generating PDF...%n");
        long start = System.currentTimeMillis();
        byte[] pdf = ApiClient.downloadPdf(printUrl, token);
        long elapsed = System.currentTimeMillis() - start;

        if (pdf != null && pdf.length > 0) {
            Path path = DemoConfig.OUTPUT_DIR.resolve("single_" + jobCode + ".pdf");
            Files.write(path, pdf);

            System.out.printf("%n  Result:%n");
            System.out.printf("    Status:    OK%n");
            System.out.printf("    Size:      %,.1f KB (%,d bytes)%n", pdf.length / 1024.0, pdf.length);
            System.out.printf("    Time:      %,d ms%n", elapsed);
            System.out.printf("    Saved:     %s%n", path.toAbsolutePath());

            // Try to open on Windows
            try {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", "", path.toString()});
            } catch (Exception ignored) {
            }
        } else {
            System.out.printf("%n  Result: FAILED (no PDF data returned) — %,d ms%n", elapsed);
        }
    }
}
