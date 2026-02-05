package com.company.mqprovisioning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProvisionRequest {

    @NotBlank(message = "Request type är obligatoriskt")
    private String requestType; // new, update

    @NotBlank(message = "Resource type är obligatoriskt")
    private String resourceType; // queue, topic

    @NotBlank(message = "Namn är obligatoriskt")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
            message = "Namn får endast innehålla bokstäver, siffror, punkt, underscore och bindestreck")
    @Size(max = 255, message = "Namn får inte vara längre än 255 tecken")
    private String name;

    private List<String> consumers;

    private List<String> producers;

    // För topics: subscription-namn (t.ex. "happening-subscription-pub-decisions")
    @Pattern(regexp = "^[a-zA-Z0-9._-]*$",
            message = "Subscription-namn får endast innehålla bokstäver, siffror, punkt, underscore och bindestreck")
    private String subscriptionName;

    private String description;

    @NotBlank(message = "Team är obligatoriskt")
    private String team;

    @NotBlank(message = "Beställare är obligatoriskt")
    private String requester;

    @NotBlank(message = "Ärendenummer är obligatoriskt")
    @Pattern(regexp = "^[a-zA-Z0-9_-]+$",
            message = "Ärendenummer får endast innehålla bokstäver, siffror, underscore och bindestreck")
    @Size(max = 50, message = "Ärendenummer får inte vara längre än 50 tecken")
    private String ticketNumber;

    public boolean hasConsumersOrProducers() {
        return (consumers != null && !consumers.isEmpty()) ||
                (producers != null && !producers.isEmpty());
    }
}