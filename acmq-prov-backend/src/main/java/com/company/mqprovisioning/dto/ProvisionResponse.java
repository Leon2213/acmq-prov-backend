package com.company.mqprovisioning.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProvisionResponse {

    private String requestId;
    private String status; // success, error, pending
    private String message;
    private List<String> pullRequests;
    private LocalDateTime timestamp;
    private boolean ok; // Frontend förväntar sig detta fält

    public static ProvisionResponse success(String requestId, List<String> pullRequests) {
        return ProvisionResponse.builder()
                .requestId(requestId)
                .status("success")
                .ok(true)
                .message("Beställning skapad framgångsrikt")
                .pullRequests(pullRequests)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ProvisionResponse error(String message) {
        return ProvisionResponse.builder()
                .status("error")
                .ok(false)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static ProvisionResponse pending(String requestId, String message) {
        return ProvisionResponse.builder()
                .requestId(requestId)
                .status("pending")
                .ok(true)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
