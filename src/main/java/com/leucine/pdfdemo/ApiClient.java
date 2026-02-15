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
        return apiGet(url, token, DemoConfig.FACILITY_ID);
    }

    public static JsonNode apiGet(String url, String token, String facilityId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("facilityId", facilityId)
                .timeout(Duration.ofSeconds(DemoConfig.API_TIMEOUT_SECONDS))
                .GET()
                .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return MAPPER.readTree(response.body());
            } else if (response.statusCode() == 403) {
                System.out.printf("  API Error: HTTP %d (unauthorized)%n", response.statusCode());
            } else {
                System.out.printf("  API Error: HTTP %d%n", response.statusCode());
            }
        } catch (Exception e) {
            System.out.printf("  API Error: %s%n", e.getMessage());
        }
        return null;
    }

    public static byte[] downloadPdf(String url, String token) {
        return downloadPdf(url, token, DemoConfig.FACILITY_ID);
    }

    public static byte[] downloadPdf(String url, String token, String facilityId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .header("facilityId", facilityId)
                .timeout(Duration.ofSeconds(DemoConfig.PDF_DOWNLOAD_TIMEOUT_SECONDS))
                .GET()
                .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                return response.body();
            } else {
                System.out.printf("  HTTP %d%n", response.statusCode());
            }
        } catch (Exception e) {
            System.out.printf("  Download Error: %s%n", e.getMessage());
        }
        return null;
    }
}
