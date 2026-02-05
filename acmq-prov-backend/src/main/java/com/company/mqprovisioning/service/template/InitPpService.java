package com.company.mqprovisioning.service.template;

import com.company.mqprovisioning.dto.ProvisionRequest;
import com.company.mqprovisioning.dto.SubscriptionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service för att uppdatera Puppet init.pp med variabler för köer och topics.
 * Variabler används sedan i broker.xml.erb templates.
 * Bevarar hela filens struktur - lägger till nya deklarationer och valideringar.
 *
 * Format:
 * $address_pensionsratt_queue_kontrakt_overforda_prepratter = 'pensionsratt.queue.kontrakt.overforda.prepratter',
 * $anycast_pensionsratt_queue_kontrakt_overforda_prepratter = 'pensionsratt.queue.kontrakt.overforda.prepratter',
 */
@Slf4j
@Service
public class InitPpService {

    private static final Pattern CLASS_PARAMS_PATTERN = Pattern.compile(
            "class\\s+icc_artemis_broker\\s*\\((.*?)\\)\\s*\\{",
            Pattern.DOTALL
    );

    private static final Pattern VALIDATES_SECTION_PATTERN = Pattern.compile(
            "(#\\s*VALIDATES\\s*\\n)(.*?)(#\\s*REPOS)",
            Pattern.DOTALL
    );

    public String updateInitPp(String existingContent, ProvisionRequest request) {
        log.info("Updating init.pp for queue/topic: {}", request.getName());

        if (existingContent == null || existingContent.trim().isEmpty()) {
            throw new IllegalArgumentException("Existing init.pp content cannot be empty");
        }

        // Konvertera könamn till variabel-namn (t.ex. pensionsratt.queue.test -> pensionsratt_queue_test)
        String variableName = convertToVariableName(request.getName());

        // Kolla om address-variabeln redan finns
        String addressVarPattern = "\\$address_" + Pattern.quote(variableName) + "\\s*=";
        boolean addressExists = existingContent.matches("(?s).*" + addressVarPattern + ".*");

        // Hitta vilka nya subscriptions som saknas i init.pp
        List<SubscriptionInfo> missingSubscriptions = findMissingSubscriptions(existingContent, request);

        // Om address finns och inga subscriptions saknas, returnera
        if (addressExists && missingSubscriptions.isEmpty()) {
            log.info("All variables already exist in init.pp for {}", request.getName());
            return existingContent;
        }

        String updatedContent = existingContent;

        // Scenario 1: Address finns inte - lägg till allt (address + alla nya subscriptions)
        if (!addressExists) {
            log.info("Variable $address_{} does not exist, adding all variables", variableName);
            updatedContent = addClassParameters(updatedContent, variableName, request);
            updatedContent = addValidations(updatedContent, variableName, request);
        }
        // Scenario 2: Address finns men subscriptions saknas - lägg till endast saknade subscriptions
        else if (!missingSubscriptions.isEmpty()) {
            log.info("Address exists but {} subscription variable(s) are missing, adding them", missingSubscriptions.size());
            for (SubscriptionInfo subscription : missingSubscriptions) {
                String subscriptionVarName = convertToVariableName(subscription.getSubscriptionName());
                log.info("Adding missing subscription variable: $multicast_{}", subscriptionVarName);
                updatedContent = addSubscriptionParameter(updatedContent, subscriptionVarName, subscription.getSubscriptionName());
                updatedContent = addSubscriptionValidation(updatedContent, subscriptionVarName);
            }
        }

        return updatedContent;
    }

    /**
     * Hittar vilka nya subscriptions som saknas i init.pp.
     * Returnerar lista av SubscriptionInfo för subscriptions där variabeln inte finns.
     */
    private List<SubscriptionInfo> findMissingSubscriptions(String existingContent, ProvisionRequest request) {
        List<SubscriptionInfo> missing = new ArrayList<>();

        // Hantera nya subscriptions-listan
        if (request.hasNewSubscriptions()) {
            for (SubscriptionInfo subscription : request.getNewSubscriptions()) {
                String subscriptionVarName = convertToVariableName(subscription.getSubscriptionName());
                String subscriptionVarPattern = "\\$multicast_" + Pattern.quote(subscriptionVarName) + "\\s*=";
                boolean exists = existingContent.matches("(?s).*" + subscriptionVarPattern + ".*");
                if (!exists) {
                    missing.add(subscription);
                }
            }
        }
        // Fallback: hantera gamla subscriptionName-fältet (bakåtkompatibilitet)
        else if ("topic".equals(request.getResourceType()) &&
                request.getSubscriptionName() != null && !request.getSubscriptionName().isEmpty()) {
            String subscriptionVarName = convertToVariableName(request.getSubscriptionName());
            String subscriptionVarPattern = "\\$multicast_" + Pattern.quote(subscriptionVarName) + "\\s*=";
            boolean exists = existingContent.matches("(?s).*" + subscriptionVarPattern + ".*");
            if (!exists) {
                // Skapa en temporär SubscriptionInfo för bakåtkompatibilitet
                missing.add(SubscriptionInfo.builder()
                        .subscriptionName(request.getSubscriptionName())
                        .subscriber("legacy") // Placeholder
                        .isNew(true)
                        .build());
            }
        }

        return missing;
    }

    private String convertToVariableName(String queueName) {
        // Konvertera punkter och bindestreck till underscore
        // pensionsratt.queue.kontrakt.test -> pensionsratt_queue_kontrakt_test
        return queueName.replaceAll("[.\\-]", "_");
    }

    /**
     * Lägger till nya variabeldeklarationer i class-parametrarna
     */
    private String addClassParameters(String content, String variableName, ProvisionRequest request) {
        Matcher matcher = CLASS_PARAMS_PATTERN.matcher(content);

        if (!matcher.find()) {
            log.warn("Could not find class parameter section in init.pp");
            return content;
        }

        String classParams = matcher.group(1);
        int classStartPos = matcher.start();
        int classEndPos = matcher.end();

        // Hitta sista variabeldeklarationen (den sista raden som innehåller $ = )
        String[] lines = classParams.split("\n");
        int lastParamLineIndex = -1;

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.contains("$") && line.contains("=")) {
                lastParamLineIndex = i;
                break;
            }
        }

        // Detektera alignment-kolumnen för '=' från befintliga rader
        int alignmentColumn = detectAlignmentColumn(classParams);
        log.debug("Detected alignment column for '=': {}", alignmentColumn);

        // Skapa nya variabeldeklarationer med korrekt alignment
        List<String> newLines = generateParameterLines(variableName, request, alignmentColumn);

        // Infoga nya deklarationer efter sista befintliga deklaration
        StringBuilder newClassParams = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            newClassParams.append(lines[i]);
            if (i < lines.length - 1) {
                newClassParams.append("\n");
            }

            // Efter sista parameter-raden, lägg till nya deklarationer
            if (i == lastParamLineIndex) {
                newClassParams.append("\n\n"); // Tom rad före nya deklarationer
                for (String newLine : newLines) {
                    newClassParams.append(newLine).append("\n");
                }
            }
        }

        // Ersätt class-parameter-sektionen
        String before = content.substring(0, classStartPos);
        String after = content.substring(classEndPos);

        String result = before + "class icc_artemis_broker (" + newClassParams.toString() + ") {" + after;

        log.info("Added {} parameter declarations for {}", newLines.size(), variableName);
        return result;
    }

    /**
     * Detekterar vilken kolumn '=' tecken är alignerade till i befintlig fil.
     * Använder medianen av positionerna för att undvika outliers.
     */
    private int detectAlignmentColumn(String classParams) {
        Pattern alignmentPattern = Pattern.compile("^(\\s*\\$[a-zA-Z0-9_]+)(\\s+)=", Pattern.MULTILINE);
        Matcher matcher = alignmentPattern.matcher(classParams);

        List<Integer> positions = new ArrayList<>();
        while (matcher.find()) {
            // Räkna visuell position (expandera tabs till 4 spaces)
            String beforeEquals = matcher.group(1) + matcher.group(2);
            int visualPosition = 0;
            for (char c : beforeEquals.toCharArray()) {
                if (c == '\t') {
                    visualPosition += 4 - (visualPosition % 4); // Tab till nästa 4-position
                } else {
                    visualPosition++;
                }
            }
            positions.add(visualPosition);
        }

        if (positions.isEmpty()) {
            return 60; // Default
        }

        // Använd den mest förekommande positionen (mode) för konsistens
        java.util.Collections.sort(positions);
        // Ta medianen för att undvika outliers
        int medianIndex = positions.size() / 2;
        // Lägg till lite extra padding för att matcha befintlig stil
        return positions.get(medianIndex) + 4;
    }

    /**
     * Genererar parameterdeklarationer baserat på resource type med korrekt alignment
     */
    private List<String> generateParameterLines(String variableName, ProvisionRequest request, int alignmentColumn) {
        List<String> lines = new ArrayList<>();

        String resourceName = request.getName();

        if ("queue".equals(request.getResourceType())) {
            // För ANYCAST (queue)
            lines.add(formatParameterLine("address", variableName, resourceName, alignmentColumn));
            lines.add(formatParameterLine("anycast", variableName, resourceName, alignmentColumn));
        } else {
            // För MULTICAST (topic)
            // Address-variabel för topic
            lines.add(formatParameterLine("address", variableName, resourceName, alignmentColumn));

            // Lägg till subscription-variabler från nya subscriptions-listan
            if (request.hasNewSubscriptions()) {
                for (SubscriptionInfo subscription : request.getNewSubscriptions()) {
                    String subscriptionVarName = convertToVariableName(subscription.getSubscriptionName());
                    lines.add(formatParameterLine("multicast", subscriptionVarName, subscription.getSubscriptionName(), alignmentColumn));
                }
            }
            // Fallback: hantera gamla subscriptionName-fältet (bakåtkompatibilitet)
            else if (request.getSubscriptionName() != null && !request.getSubscriptionName().isEmpty()) {
                String subscriptionVarName = convertToVariableName(request.getSubscriptionName());
                lines.add(formatParameterLine("multicast", subscriptionVarName, request.getSubscriptionName(), alignmentColumn));
            }
        }

        return lines;
    }

    /**
     * Formaterar en parameter-rad med korrekt alignment för '=' tecknet
     */
    private String formatParameterLine(String prefix, String variableName, String value, int alignmentColumn) {
        String varPart = String.format("  $%s_%s", prefix, variableName);
        int paddingNeeded = alignmentColumn - varPart.length();

        // Säkerställ minst ett mellanslag
        if (paddingNeeded < 1) {
            paddingNeeded = 1;
        }

        String padding = " ".repeat(paddingNeeded);
        return String.format("%s%s= '%s',", varPart, padding, value);
    }

    /**
     * Lägger till validate_string() anrop för nya variabler
     */
    private String addValidations(String content, String variableName, ProvisionRequest request) {
        Matcher matcher = VALIDATES_SECTION_PATTERN.matcher(content);

        if (!matcher.find()) {
            log.warn("Could not find VALIDATES section in init.pp");
            return content;
        }

        String validatesHeader = matcher.group(1);
        String validatesBody = matcher.group(2);
        String reposHeader = matcher.group(3);

        int sectionStart = matcher.start();
        int sectionEnd = matcher.end();

        // Skapa nya validerings-rader
        List<String> newValidations = generateValidationLines(variableName, request);

        // Lägg till nya valideringar i slutet av VALIDATES-sektionen
        // Behåll befintlig indentering genom att inte trimma bort leading whitespace
        String trimmedBody = validatesBody.stripTrailing(); // Endast ta bort trailing whitespace
        StringBuilder newValidatesBody = new StringBuilder(trimmedBody);
        newValidatesBody.append("\n");
        for (String validation : newValidations) {
            newValidatesBody.append("  ").append(validation).append("\n");
        }
        newValidatesBody.append("\n");

        // Ersätt VALIDATES-sektionen
        String before = content.substring(0, sectionStart);
        String after = content.substring(sectionEnd - reposHeader.length()); // Behåll "# REPOS"

        String result = before + validatesHeader + newValidatesBody.toString() + after;

        log.info("Added {} validation statements for {}", newValidations.size(), variableName);
        return result;
    }

    /**
     * Genererar validate_string() anrop för varje variabel
     */
    private List<String> generateValidationLines(String variableName, ProvisionRequest request) {
        List<String> validations = new ArrayList<>();

        if ("queue".equals(request.getResourceType())) {
            validations.add(String.format("validate_string($address_%s)", variableName));
            validations.add(String.format("validate_string($anycast_%s)", variableName));
        } else {
            // För topic: address-variabel
            validations.add(String.format("validate_string($address_%s)", variableName));

            // Lägg till validering för alla nya subscriptions
            if (request.hasNewSubscriptions()) {
                for (SubscriptionInfo subscription : request.getNewSubscriptions()) {
                    String subscriptionVarName = convertToVariableName(subscription.getSubscriptionName());
                    validations.add(String.format("validate_string($multicast_%s)", subscriptionVarName));
                }
            }
            // Fallback: hantera gamla subscriptionName-fältet (bakåtkompatibilitet)
            else if (request.getSubscriptionName() != null && !request.getSubscriptionName().isEmpty()) {
                String subscriptionVarName = convertToVariableName(request.getSubscriptionName());
                validations.add(String.format("validate_string($multicast_%s)", subscriptionVarName));
            }
        }

        return validations;
    }

    /**
     * Lägger till endast en subscription-variabel i class-parametrar.
     * Används när topic redan finns men en ny subscription läggs till.
     */
    private String addSubscriptionParameter(String content, String subscriptionVarName, String subscriptionValue) {
        Matcher matcher = CLASS_PARAMS_PATTERN.matcher(content);

        if (!matcher.find()) {
            log.warn("Could not find class parameter section in init.pp");
            return content;
        }

        String classParams = matcher.group(1);
        int classStartPos = matcher.start();
        int classEndPos = matcher.end();

        // Hitta sista variabeldeklarationen
        String[] lines = classParams.split("\n");
        int lastParamLineIndex = -1;

        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.contains("$") && line.contains("=")) {
                lastParamLineIndex = i;
                break;
            }
        }

        // Detektera alignment-kolumnen för '=' från befintliga rader
        int alignmentColumn = detectAlignmentColumn(classParams);

        // Skapa subscription-variabeldeklaration
        String newLine = formatParameterLine("multicast", subscriptionVarName, subscriptionValue, alignmentColumn);

        // Infoga nya deklarationen efter sista befintliga deklaration
        StringBuilder newClassParams = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            newClassParams.append(lines[i]);
            if (i < lines.length - 1) {
                newClassParams.append("\n");
            }

            // Efter sista parameter-raden, lägg till ny deklaration
            if (i == lastParamLineIndex) {
                newClassParams.append("\n\n"); // Tom rad före nya deklarationen
                newClassParams.append(newLine).append("\n");
            }
        }

        // Ersätt class-parameter-sektionen
        String before = content.substring(0, classStartPos);
        String after = content.substring(classEndPos);

        String result = before + "class icc_artemis_broker (" + newClassParams.toString() + ") {" + after;

        log.info("Added subscription parameter declaration for multicast_{}", subscriptionVarName);
        return result;
    }

    /**
     * Lägger till endast en subscription-validering.
     * Används när topic redan finns men en ny subscription läggs till.
     */
    private String addSubscriptionValidation(String content, String subscriptionVarName) {
        Matcher matcher = VALIDATES_SECTION_PATTERN.matcher(content);

        if (!matcher.find()) {
            log.warn("Could not find VALIDATES section in init.pp");
            return content;
        }

        String validatesHeader = matcher.group(1);
        String validatesBody = matcher.group(2);
        String reposHeader = matcher.group(3);

        int sectionStart = matcher.start();
        int sectionEnd = matcher.end();

        // Lägg till ny validering i slutet av VALIDATES-sektionen
        String trimmedBody = validatesBody.stripTrailing();
        StringBuilder newValidatesBody = new StringBuilder(trimmedBody);
        newValidatesBody.append("\n");
        newValidatesBody.append("  ").append(String.format("validate_string($multicast_%s)", subscriptionVarName)).append("\n");
        newValidatesBody.append("\n");

        // Ersätt VALIDATES-sektionen
        String before = content.substring(0, sectionStart);
        String after = content.substring(sectionEnd - reposHeader.length());

        String result = before + validatesHeader + newValidatesBody.toString() + after;

        log.info("Added subscription validation for multicast_{}", subscriptionVarName);
        return result;
    }
}