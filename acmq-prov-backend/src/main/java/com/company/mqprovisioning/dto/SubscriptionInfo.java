package com.company.mqprovisioning.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Representerar en subscription med dess subscriber (konsumerande applikation/roll).
 * Används för topics med multicast subscriptions.
 *
 * Exempel från frontend:
 * {
 *   "subscriptionName": "newsletter-subscription",
 *   "subscriber": "notification-service",
 *   "isNew": true
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubscriptionInfo {

    /**
     * Namnet på subscription (t.ex. "newsletter-subscription", "order-events-sub")
     */
    @NotBlank(message = "Subscription-namn är obligatoriskt")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
            message = "Subscription-namn får endast innehålla bokstäver, siffror, punkt, underscore och bindestreck")
    private String subscriptionName;

    /**
     * Subscriber/konsument som ska ha tillgång till denna subscription.
     * Detta är applikationen/rollen som konsumerar meddelanden (t.ex. "notification-service", "order-processor")
     */
    @NotBlank(message = "Subscriber är obligatoriskt")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
            message = "Subscriber får endast innehålla bokstäver, siffror, punkt, underscore och bindestreck")
    private String subscriber;

    /**
     * Markerar om detta är en ny subscription som ska läggas till.
     * - true: Backend skapar security-settings, init.pp variabler, och address entries
     * - false: Subscription existerar redan, ingen ändring behövs
     *
     * OBS: @JsonProperty krävs för att Jackson ska mappa JSON-fältet "isNew" korrekt
     * eftersom Lombok genererar getter som isNew() för boolean-fält som börjar med "is".
     */
    @JsonProperty("isNew")
    private boolean isNew;
}
