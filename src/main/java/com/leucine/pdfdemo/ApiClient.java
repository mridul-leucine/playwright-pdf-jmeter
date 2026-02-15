package com.leucine.pdfdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class ApiClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(DemoConfig.API_TIMEOUT_SECONDS))
        .build();

    public static JsonNode apiGet(String url, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("facilityId", DemoConfig.FACILITY_ID)
                .timeout(Duration.ofSeconds(DemoConfig.API_TIMEOUT_SECONDS))
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return MAPPER.readTree(response.body());
            } else {
                System.out.printf("  API Error: HTTP %d%n", response.statusCode());
            }
        } catch (Exception e) {
            System.out.printf("  API Error: %s%n", e.getMessage());
        }
        return null;
    }
}
