package com.leucine.pdfdemo;

public class PdfDemoApp {

    public static void main(String[] args) throws Exception {
        int port = 8080;
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            }
        }

        PdfHttpServer server = new PdfHttpServer(port);
        server.start();
        Thread.currentThread().join();
    }
}
