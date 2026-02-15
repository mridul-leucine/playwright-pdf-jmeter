package com.leucine.pdfdemo;

import java.util.Arrays;
import java.util.List;

public class PdfDemoApp {

    public static void main(String[] args) throws Exception {
        List<String> argList = Arrays.asList(args);

        if (!argList.contains("--server")) {
            System.out.println("Usage: --server [--port=N]");
            System.out.println("  Starts an HTTP server that accepts POST /pdf with job JSON body.");
            return;
        }

        int port = parseIntArg(argList, "--port=", 8080);
        PdfHttpServer server = new PdfHttpServer(port);
        server.start();
        Thread.currentThread().join();
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
