package com.leucine.pdfdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;

public class TokenManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String loadToken() {
        try {
            if (Files.exists(DemoConfig.CONFIG_PATH)) {
                JsonNode node = MAPPER.readTree(DemoConfig.CONFIG_PATH.toFile());
                if (node.has("token")) {
                    return node.get("token").asText();
                }
            }
        } catch (Exception e) {
            System.out.printf("  Warning: could not read config.json: %s%n", e.getMessage());
        }
        return null;
    }

    public static void saveToken(String token) throws IOException {
        MAPPER.writerWithDefaultPrettyPrinter()
            .writeValue(DemoConfig.CONFIG_PATH.toFile(), new TokenConfig(token));
        System.out.println("  Token saved to config.json");
    }

    public static String getOrRefreshToken() throws Exception {
        String token = loadToken();
        if (token == null) {
            System.out.println("  No token found. Logging in via browser...");
            token = BrowserLogin.login();
            return token;
        }

        // Validate token with a test API call
        String testUrl = DemoConfig.STREEM_API + "/jobs/" + DemoConfig.JOB_IDS.get(0);
        JsonNode result = ApiClient.apiGet(testUrl, token);
        if (result == null) {
            System.out.println("  Token expired. Refreshing via browser login...");
            token = BrowserLogin.login();
        }
        return token;
    }

    static class TokenConfig {
        public String token;

        TokenConfig() {
        }

        TokenConfig(String token) {
            this.token = token;
        }
    }
}
