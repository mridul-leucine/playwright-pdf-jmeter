package com.leucine.pdfdemo;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class PdfDemoApp {

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);

        System.out.println();
        System.out.println("=".repeat(60));
        System.out.println("  Streem PDF Generation \u2014 Concurrent Performance Demo");
        System.out.println("=".repeat(60));

        Files.createDirectories(DemoConfig.OUTPUT_DIR);

        if (argList.contains("--emoji-test")) {
            EmojiFontTest.run();
            return;
        }

        if (argList.contains("--server")) {
            int port = parseIntArg(argList, "--port=", 8080);
            System.out.println("\n  Mode: HTTP Server (for JMeter load testing)");
            PdfHttpServer server = new PdfHttpServer(port);
            server.start();
            // Block the main thread so the server keeps running
            Thread.currentThread().join();
            return;
        }

        if (argList.contains("--login")) {
            System.out.println("\n  Mode: Browser Login");
            BrowserLogin.login();
            System.out.println("\n  Done. Token saved. You can now run other modes.");
            return;
        }

        String token = TokenManager.getOrRefreshToken();
        if (token == null) {
            System.out.println("\n  ERROR: Could not obtain a valid token.");
            System.out.println("  Run with --login first: ./gradlew run --args=\"--login\"");
            return;
        }

        String jobId = DemoConfig.JOB_IDS.get(0);
        for (String arg : argList) {
            if (arg.startsWith("--jobId=")) {
                jobId = arg.substring("--jobId=".length());
            }
        }

        int n = parseIntArg(argList, "--n=", 5);

        if (argList.contains("--benchmark")) {
            int benchN = parseIntArg(argList, "--n=", 100);
            LocalBenchmark.run(token, jobId, benchN);
            return;
        }

        if (argList.contains("--local")) {
            LocalPdfRenderer.run(token, jobId);
            return;
        }

        if (argList.contains("--single")) {
            ConcurrentPdfTest.runSingle(token, jobId);
            return;
        }

        ConcurrentPdfTest.run(token, jobId, n);
    }

    private static int parseIntArg(List<String> argList, String prefix, int defaultValue) {
        for (String arg : argList) {
            if (arg.startsWith(prefix)) {
                return Integer.parseInt(arg.substring(prefix.length()));
            }
        }
        return defaultValue;
    }
}
