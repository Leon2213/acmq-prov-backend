package com.company.mqprovisioning.controller;

import com.company.mqprovisioning.dto.ProvisionRequest;
import com.company.mqprovisioning.dto.ProvisionResponse;
import com.company.mqprovisioning.service.ProvisioningService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/mq")
@RequiredArgsConstructor
@Validated
public class MQProvisioningController {

    private final ProvisioningService provisioningService;

    @PostMapping("/provision")
    public ResponseEntity<ProvisionResponse> provisionQueue(
            @Valid @RequestBody ProvisionRequest request) {

        log.info("Received provisioning request for {} '{}'",
                request.getResourceType(), request.getName());

        try {
            ProvisionResponse response = provisioningService.processProvisionRequest(request);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Validation error: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(ProvisionResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Error processing provisioning request", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ProvisionResponse.error("Ett internt fel uppstod: " + e.getMessage()));
        }
    }

    @GetMapping("/status/{requestId}")
    public ResponseEntity<ProvisionResponse> getStatus(@PathVariable String requestId) {
        log.info("Checking status for request: {}", requestId);

        try {
            ProvisionResponse response = provisioningService.getRequestStatus(requestId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error getting status for request: {}", requestId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ProvisionResponse.error("Kunde inte hämta status"));
        }
    }

    @GetMapping("/validate/{queueName}")
    public ResponseEntity<Boolean> validateQueueName(@PathVariable String queueName) {
        boolean isValid = provisioningService.validateQueueName(queueName);
        return ResponseEntity.ok(isValid);
    }
}
