package com.leucine.pdfdemo;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

public class PdfDemoApp {

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);

        Files.createDirectories(DemoConfig.OUTPUT_DIR);

        if (argList.contains("--server")) {
            int port = parseIntArg(argList, "--port=", 8080);
            PdfHttpServer server = new PdfHttpServer(port);
            server.start();
            Thread.currentThread().join();
            return;
        }

        if (argList.contains("--login")) {
            BrowserLogin.login();
            System.out.println("Done. Token saved.");
            return;
        }

        String token = TokenManager.getOrRefreshToken();
        if (token == null) {
            System.out.println("ERROR: No valid token. Run with --login first.");
            return;
        }

        String jobId = DemoConfig.JOB_IDS.get(0);
        for (String arg : argList) {
            if (arg.startsWith("--jobId=")) {
                jobId = arg.substring("--jobId=".length());
            }
        }

        LocalPdfRenderer.run(token, jobId);
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
