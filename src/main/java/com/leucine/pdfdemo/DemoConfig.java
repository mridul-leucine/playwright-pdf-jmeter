package com.leucine.pdfdemo;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public final class DemoConfig {

    public static final String STREEM_API = "https://api.qa.platform.leucinetech.com/v1";
    public static final String FRONTEND_URL = "https://qa.platform.leucinetech.com";
    public static final String FACILITY_ID = "1616367803";
    public static final String USERNAME = "mridul.01";
    public static final String PASSWORD = "Unicorn@2025";

    public static final List<String> JOB_IDS = List.of(
        "722492974613180416",
        "722485307568074752"
    );

    public static final Path PROJECT_ROOT = Paths.get(System.getProperty("user.dir"));
    public static final Path OUTPUT_DIR = PROJECT_ROOT.resolve("output");
    public static final Path CONFIG_PATH = PROJECT_ROOT.resolve("config.json");

    public static final int API_TIMEOUT_SECONDS = 30;

    private DemoConfig() {
    }
}
