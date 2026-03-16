package com.company.mqprovisioning.service;

import com.company.mqprovisioning.dto.ProvisionRequest;
import com.company.mqprovisioning.dto.ProvisionResponse;
import com.company.mqprovisioning.dto.SubscriptionInfo;
import com.company.mqprovisioning.service.git.GitService;
import com.company.mqprovisioning.service.template.AcmqYamlService;
import com.company.mqprovisioning.service.template.InitPpService;
import com.company.mqprovisioning.service.template.BrokerXmlTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProvisioningService {

    private final GitService gitService;
    private final AcmqYamlService acmqYamlService;
    private final InitPpService initPpService;
    private final BrokerXmlTemplateService brokerXmlTemplateService;

    // Simple in-memory cache för status tracking
    private final ConcurrentHashMap<String, ProvisionResponse> requestCache = new ConcurrentHashMap<>();

    public ProvisionResponse processProvisionRequest(ProvisionRequest request) {

        // Validera request
        validateRequest(request);

        // Filtrera bort "admin" från producers och consumers - admin hanteras automatiskt
        filterAdminFromRequest(request);

        String requestId = UUID.randomUUID().toString();
        log.info("Processing request {} for {}", requestId, request.getName());

        try {
            List<String> pullRequestUrls = new ArrayList<>();

            // 1. Uppdatera Hieradata repo (returnerar null om inga ändringar behövs)
            log.info("Checking hieradata repository for request {}", requestId);
            String hieradataPR = updateHieradataRepo(request, requestId);
            if (hieradataPR != null) {
                pullRequestUrls.add(hieradataPR);
                log.info("Created hieradata PR for request {}", requestId);
            }

            // 2. Uppdatera Puppet broker.xml.erb repo (returnerar null om inga ändringar behövs)
            log.info("Checking puppet repository for request {}", requestId);
            String brokerXmlPR = updateBrokerXmlRepo(request, requestId);
            if (brokerXmlPR != null) {
                pullRequestUrls.add(brokerXmlPR);
                log.info("Created puppet PR for request {}", requestId);
            }

            ProvisionResponse response = ProvisionResponse.success(requestId, pullRequestUrls);
            requestCache.put(requestId, response);

            log.info("Successfully created provisioning request {} with {} PRs: {}",
                    requestId, pullRequestUrls.size(), pullRequestUrls);

            return response;

        } catch (Exception e) {
            log.error("Error processing provisioning request {}", requestId, e);
            ProvisionResponse errorResponse = ProvisionResponse.error(
                    "Fel vid skapande av ändringar: " + e.getMessage()
            );
            requestCache.put(requestId, errorResponse);
            throw new RuntimeException("Kunde inte skapa provisioning request", e);
        }
    }

    private static final String HIERADATA_TEST_PATH = "role/acmq.yaml";
    private static final String HIERADATA_PROD_PATH = "pm_env/pro/acmq.yaml";

    private String updateHieradataRepo(ProvisionRequest request, String requestId) {
        // Använd ärendenummer för branchnamn
        String branchName = String.format("feature/%s", request.getTicketNumber());

        // 1. Clone/pull hieradata repo
        gitService.prepareRepoHieradata();

        // 2. Uppdatera role/acmq.yaml (test): användare, roller och subscription enabled-flaggor
        String existingTestContent = gitService.readFile("hieradata", HIERADATA_TEST_PATH);
        String updatedTestContent = acmqYamlService.updateAcmqYaml(existingTestContent, request);
        updatedTestContent = acmqYamlService.updateSubscriptionEnabledFlags(
                updatedTestContent, request.getSubscriptions(), false);

        // 3. Uppdatera pm_env/pro/acmq.yaml (prod): subscription enabled-flaggor
        String existingProdContent = gitService.readFile("hieradata", HIERADATA_PROD_PATH);
        String updatedProdContent = acmqYamlService.updateSubscriptionEnabledFlags(
                existingProdContent, request.getSubscriptions(), true);

        boolean testChanged = !existingTestContent.equals(updatedTestContent);
        boolean prodChanged = !existingProdContent.equals(updatedProdContent);

        // 4. Kolla om det finns några ändringar - hoppa över PR om inga ändringar
        if (!testChanged && !prodChanged) {
            log.info("No changes needed in hieradata for request {} - all users/roles already exist", requestId);
            return null; // Ingen PR behövs
        }

        // 5. Skapa ny branch och genomför ändringar
        gitService.createBranchHieradata(branchName);
        if (testChanged) {
            gitService.overwriteFile("hieradata", HIERADATA_TEST_PATH, updatedTestContent);
            log.info("Updated hieradata test file ({}) for request {}", HIERADATA_TEST_PATH, requestId);
        }
        if (prodChanged) {
            gitService.overwriteFile("hieradata", HIERADATA_PROD_PATH, updatedProdContent);
            log.info("Updated hieradata prod file ({}) for request {}", HIERADATA_PROD_PATH, requestId);
        }

        // 6. Commit och push
        String subscriptionChanges = buildSubscriptionChangeSummary(request);
        String commitMessage = String.format(
                "[%s] %s %s '%s'\n\n" +
                        "Ticket: %s\nRequestor: %s\nTeam: %s\n\n" +
                        "Producers: %s\nConsumers: %s%s",
                request.getTicketNumber(),
                subscriptionChanges.isEmpty() ? "Add users and roles for" : "Update subscription for",
                request.getResourceType(), request.getName(),
                request.getTicketNumber(),
                request.getRequester(), request.getTeam(),
                request.getProducers() != null ? String.join(", ", request.getProducers()) : "none",
                request.getConsumers() != null ? String.join(", ", request.getConsumers()) : "none",
                subscriptionChanges.isEmpty() ? "" : "\n\nSubscription changes:\n" + subscriptionChanges
        );
        gitService.commitAndPush("hieradata", branchName, commitMessage);

        // 7. Skapa Pull Request
//        String prUrl = gitService.createPullRequest(
//            "hieradata",
//            branchName,
//            "master",
//            String.format("MQ Provisioning: Users and roles for %s", request.getName()),
//            generatePRDescription(request, requestId)
//        );

        return "PR for hieradata-url to fix later";
    }

    private String updateBrokerXmlRepo(ProvisionRequest request, String requestId) {
        // Använd ärendenummer för branchnamn
        String branchName = String.format("feature/%s", request.getTicketNumber());

        // 1. Clone/pull puppet repo
        gitService.prepareRepoPuppet();

        // 2. Skapa ny branch
        gitService.createBranchPuppet(branchName);

        // 3. Uppdatera init.pp med variabler
        String existingInitPp = gitService.readFile("puppet", "modules/icc_artemis_broker/manifests/init.pp");
        String updatedInitPp = initPpService.updateInitPp(existingInitPp, request);
        gitService.overwriteFile("puppet", "modules/icc_artemis_broker/manifests/init.pp", updatedInitPp);

        // 4. Uppdatera broker.xml.erb med security settings
        String brokerXmlPath = "modules/icc_artemis_broker/templates/brokers/etc/broker.xml.erb";
        String existingBrokerXml = gitService.readFile("puppet", brokerXmlPath);

        String updatedBrokerXml = existingBrokerXml;
        String variableName = brokerXmlTemplateService.getVariableName(request);

        // Kolla om resurs-specifik security setting redan finns
        boolean resourceSecurityExists = brokerXmlTemplateService.checkResourceSecuritySettingExists(existingBrokerXml, variableName);

        if (resourceSecurityExists) {
            // Uppdatera befintlig security-setting med nya producers/consumers
            log.info("Updating existing security-setting for {} with new producers/consumers", request.getName());
            updatedBrokerXml = brokerXmlTemplateService.updateExistingSecuritySetting(updatedBrokerXml, request);
        } else {
            // Skapa nya security settings
            String newSecuritySettings = brokerXmlTemplateService.generateSecuritySettingsToAdd(existingBrokerXml, request);
            if (newSecuritySettings != null && !newSecuritySettings.trim().isEmpty()) {
                updatedBrokerXml = insertSecuritySettings(existingBrokerXml, newSecuritySettings);
            } else {
                log.info("No new security settings to add for {}", request.getName());
            }
        }

        // 4b. För topics: lägg till security-settings för varje subscription som saknas
        // Använder ALLA subscriptions (isNew-flaggan är opålitlig från frontend)
        if ("topic".equals(request.getResourceType()) && request.hasSubscriptions()) {
            List<SubscriptionInfo> subsWithName = request.getSubscriptions().stream()
                    .filter(s -> s.getSubscriptionName() != null && !s.getSubscriptionName().isEmpty())
                    .toList();
            for (SubscriptionInfo subscription : subsWithName) {
                log.info("Processing subscription: {} with subscriber: {}",
                        subscription.getSubscriptionName(), subscription.getSubscriber());

                if (!brokerXmlTemplateService.checkSubscriptionSecuritySettingExists(
                        updatedBrokerXml, request.getName(), subscription.getSubscriptionName())) {
                    log.info("Adding subscription security-setting for {}::{}",
                            request.getName(), subscription.getSubscriptionName());
                    String subscriptionSecuritySetting = brokerXmlTemplateService.generateSubscriptionSecuritySetting(
                            request, subscription);
                    if (subscriptionSecuritySetting != null && !subscriptionSecuritySetting.trim().isEmpty()) {
                        updatedBrokerXml = insertSecuritySettings(updatedBrokerXml, subscriptionSecuritySetting);
                    }
                } else {
                    log.info("Subscription security-setting already exists for {}::{}",
                            request.getName(), subscription.getSubscriptionName());
                }
            }
        }

        // 5. Lägg till address entry i <addresses> sektionen - endast om den inte redan finns
        if (!brokerXmlTemplateService.checkAddressExists(existingBrokerXml, request)) {
            String newAddressEntry = brokerXmlTemplateService.generateAddressEntry(request);
            updatedBrokerXml = insertAddress(updatedBrokerXml, newAddressEntry);
        } else {
            log.info("Address entry already exists for {}", request.getName());
            // 5b. För topics: lägg till subscription queues som saknas i existerande address
            // Använder ALLA subscriptions (isNew-flaggan är opålitlig från frontend)
            if ("topic".equals(request.getResourceType()) && request.hasSubscriptions()) {
                for (SubscriptionInfo subscription : request.getSubscriptions()) {
                    if (subscription.getSubscriptionName() == null || subscription.getSubscriptionName().isEmpty()) {
                        continue;
                    }
                    if (!brokerXmlTemplateService.checkSubscriptionQueueExistsInAddress(
                            updatedBrokerXml, request.getName(), subscription.getSubscriptionName())) {
                        log.info("Adding subscription queue to existing address for {}::{}",
                                request.getName(), subscription.getSubscriptionName());
                        updatedBrokerXml = brokerXmlTemplateService.addSubscriptionQueueToExistingAddress(
                                updatedBrokerXml, request.getName(), subscription.getSubscriptionName());
                    } else {
                        log.info("Subscription queue already exists in address for {}::{}",
                                request.getName(), subscription.getSubscriptionName());
                    }
                }
            }
        }

        gitService.overwriteFile("puppet", brokerXmlPath, updatedBrokerXml);

        // 5. Commit och push – bara om något faktiskt ändrats
        boolean initPpChanged = !existingInitPp.equals(updatedInitPp);
        boolean brokerXmlChanged = !existingBrokerXml.equals(updatedBrokerXml);

        if (!initPpChanged && !brokerXmlChanged) {
            log.info("No changes needed in puppet repo for request {} - skipping commit", requestId);
            return null;
        }

        String commitMessage = String.format(
                "[%s] Add config for %s '%s'\n\n" +
                        "Updated files:\n" +
                        "- init.pp: Added queue/topic variables\n" +
                        "- broker.xml.erb: Added security settings\n\n" +
                        "Ticket: %s\nRequestor: %s\nTeam: %s",
                request.getTicketNumber(),
                request.getResourceType(), request.getName(),
                request.getTicketNumber(),
                request.getRequester(), request.getTeam()
        );
        gitService.commitAndPush("puppet", branchName, commitMessage);

        // 6. Skapa Pull Request
        /*String prUrl = gitService.createPullRequest(
            "puppet",
            branchName,
            "main",
            String.format("MQ Provisioning: Puppet config for %s", request.getName()),
            generatePRDescription(request, requestId)
        );*/

        return "pr url att fixa senare";
    }

    /**
     * Bygger en rad per subscription som har fått enabled/disabled-ändring.
     * Exempel: "  - incidentprocess: test=false, prod=true"
     * Returnerar tom sträng om inga subscription-ändringar finns.
     */
    private String buildSubscriptionChangeSummary(ProvisionRequest request) {
        if (request.getSubscriptions() == null || request.getSubscriptions().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SubscriptionInfo sub : request.getSubscriptions()) {
            if (sub.isNew()) continue;
            if (sub.getTestEnabled() == null && sub.getProdEnabled() == null) continue;

            String name = sub.getSubscriptionName() != null ? sub.getSubscriptionName()
                    : sub.getSubscriber() != null ? sub.getSubscriber() : "unknown";
            StringBuilder changes = new StringBuilder();
            if (sub.getTestEnabled() != null) {
                changes.append("test=").append(sub.getTestEnabled() ? "enabled" : "disabled");
            }
            if (sub.getProdEnabled() != null) {
                if (changes.length() > 0) changes.append(", ");
                changes.append("prod=").append(sub.getProdEnabled() ? "enabled" : "disabled");
            }
            sb.append("  - ").append(name).append(": ").append(changes).append("\n");
        }
        return sb.toString();
    }

    private String generatePRDescription(ProvisionRequest request, String requestId) {
        StringBuilder description = new StringBuilder();
        description.append("## MQ Provisioning Request\n\n");
        description.append(String.format("**Request ID:** %s\n", requestId));
        description.append(String.format("**Type:** %s\n", request.getResourceType()));
        description.append(String.format("**Name:** %s\n", request.getName()));
        description.append(String.format("**Requestor:** %s\n", request.getRequester()));
        description.append(String.format("**Team:** %s\n\n", request.getTeam()));

        if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            description.append("**Consumers:**\n");
            request.getConsumers().forEach(c -> description.append(String.format("- %s\n", c)));
            description.append("\n");
        }

        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            description.append("**Producers:**\n");
            request.getProducers().forEach(p -> description.append(String.format("- %s\n", p)));
            description.append("\n");
        }

        if (request.getDescription() != null && !request.getDescription().isEmpty()) {
            description.append(String.format("**Description:**\n%s\n", request.getDescription()));
        }

        return description.toString();
    }

    private void validateRequest(ProvisionRequest request) {
        if (!request.hasConsumersOrProducers() && !request.hasSubscriptions()) {
            throw new IllegalArgumentException(
                    "Minst en konsument, producent eller subscription måste anges"
            );
        }

        if (!"queue".equals(request.getResourceType()) &&
                !"topic".equals(request.getResourceType())) {
            throw new IllegalArgumentException(
                    "Resource type måste vara 'queue' eller 'topic'"
            );
        }
    }

    public ProvisionResponse getRequestStatus(String requestId) {
        return requestCache.getOrDefault(
                requestId,
                ProvisionResponse.error("Request ID hittades inte")
        );
    }

    public boolean validateQueueName(String queueName) {
        return queueName != null && queueName.matches("^[a-zA-Z0-9._-]+$");
    }

    /**
     * Filtrera bort "admin" från producers och consumers listor.
     * Admin-rollen hanteras automatiskt av systemet och ska inte skickas
     * från frontend vid uppdateringar av köer/topics.
     */
    private void filterAdminFromRequest(ProvisionRequest request) {
        if (request.getProducers() != null) {
            List<String> filteredProducers = request.getProducers().stream()
                    .filter(producer -> !"admin".equalsIgnoreCase(producer))
                    .collect(java.util.stream.Collectors.toList());
            if (filteredProducers.size() != request.getProducers().size()) {
                log.info("Filtered out 'admin' from producers list for {}", request.getName());
            }
            request.setProducers(filteredProducers);
        }

        if (request.getConsumers() != null) {
            List<String> filteredConsumers = request.getConsumers().stream()
                    .filter(consumer -> !"admin".equalsIgnoreCase(consumer))
                    .collect(java.util.stream.Collectors.toList());
            if (filteredConsumers.size() != request.getConsumers().size()) {
                log.info("Filtered out 'admin' from consumers list for {}", request.getName());
            }
            request.setConsumers(filteredConsumers);
        }
    }

    /**
     * Infogar nya security settings i broker.xml.erb före </security-settings> taggen.
     * Detekterar indenteringen från befintliga security-setting taggar och applicerar samma indentering.
     */
    private String insertSecuritySettings(String existingContent, String newSecuritySettings) {
        if (existingContent == null || existingContent.isEmpty()) {
            log.warn("Existing broker.xml.erb content is empty, returning new content only");
            return newSecuritySettings;
        }

        // Hitta </security-settings> taggen och infoga före den
        String closingTag = "</security-settings>";
        int insertPosition = existingContent.lastIndexOf(closingTag);

        if (insertPosition == -1) {
            log.warn("Could not find </security-settings> tag in broker.xml.erb, appending to end");
            return existingContent + "\n\n" + newSecuritySettings;
        }

        // Detektera indentering från befintliga <security-setting> taggar
        String baseIndent = detectSecuritySettingIndent(existingContent);

        // Ta bort ett mellanslag om det finns (matchar befintlig filstandard)
        if (baseIndent.length() > 0 && baseIndent.endsWith(" ")) {
            baseIndent = baseIndent.substring(0, baseIndent.length() - 1);
        }
        log.debug("Using security-setting indent: '{}' ({} chars)", baseIndent, baseIndent.length());

        // Applicera indentering på nya security settings
        String indentedNewSettings = applyIndentation(newSecuritySettings, baseIndent);

        // Ta bort trailing whitespace före </security-settings> för att undvika extra radbrytningar
        String beforeInsert = existingContent.substring(0, insertPosition);
        // Behåll endast en radbrytning (ta bort extra whitespace men behåll \n)
        beforeInsert = beforeInsert.replaceAll("\\s+$", "") + "\n";

        // Infoga nya security settings precis före </security-settings>
        StringBuilder result = new StringBuilder();
        result.append(beforeInsert);
        result.append(indentedNewSettings);
        result.append("\n");
        result.append(existingContent.substring(insertPosition));

        return result.toString();
    }

    /**
     * Detekterar indenteringen som används för <security-setting> taggar i befintlig fil.
     * Söker efter mönstret: whitespace följt av <security-setting
     */
    private String detectSecuritySettingIndent(String content) {
        // Hitta en rad som börjar med whitespace + <security-setting
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^([ \\t]*)<security-setting\\s",
                java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            return matcher.group(1);
        }

        // Fallback: använd 8 mellanslag om ingen indentering hittas
        return "        ";
    }

    /**
     * Applicerar given indentering på varje rad i content.
     * Strippar befintlig indentering och lägger till korrekt ny indentering:
     * - security-setting taggar: baseIndent
     * - permission och andra inre taggar: baseIndent + 2 mellanslag
     * - kommentarer: baseIndent
     */
    private String applyIndentation(String content, String baseIndent) {
        StringBuilder result = new StringBuilder();
        // Normalisera radbrytningar (ta bort \r)
        String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalizedContent.split("\n");
        String innerIndent = baseIndent + "  "; // 2 extra mellanslag för inre element

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();

            // Hoppa över tomma rader
            if (trimmedLine.isEmpty()) {
                result.append("\n");
                continue;
            }

            // Bestäm indentering baserat på elementtyp
            if (trimmedLine.startsWith("<security-setting") || trimmedLine.startsWith("</security-setting")) {
                // Yttre element: använd baseIndent
                result.append(baseIndent).append(trimmedLine);
            } else if (trimmedLine.startsWith("<permission") || trimmedLine.startsWith("<!--")) {
                // Inre element och kommentarer: använd innerIndent
                result.append(innerIndent).append(trimmedLine);
            } else if (trimmedLine.startsWith("<")) {
                // Andra XML-element: använd innerIndent
                result.append(innerIndent).append(trimmedLine);
            } else {
                // Icke-XML innehåll: behåll som det är
                result.append(trimmedLine);
            }

            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Infogar ny address entry i broker.xml.erb före </addresses> taggen.
     * Detekterar indenteringen från befintliga address taggar.
     */
    private String insertAddress(String existingContent, String newAddressEntry) {
        if (existingContent == null || existingContent.isEmpty()) {
            log.warn("Existing broker.xml.erb content is empty");
            return existingContent;
        }

        // Hitta </addresses> taggen och infoga före den
        String closingTag = "</addresses>";
        int insertPosition = existingContent.lastIndexOf(closingTag);

        if (insertPosition == -1) {
            log.warn("Could not find </addresses> tag in broker.xml.erb");
            return existingContent;
        }

        // Detektera indentering från befintliga <address> taggar
        String baseIndent = detectAddressIndent(existingContent);
        log.debug("Using address indent: '{}' ({} chars)", baseIndent, baseIndent.length());

        // Applicera indentering på ny address entry
        String indentedAddressEntry = applyAddressIndentation(newAddressEntry, baseIndent);

        // Ta bort trailing whitespace före </addresses>
        String beforeInsert = existingContent.substring(0, insertPosition);
        beforeInsert = beforeInsert.replaceAll("\\s+$", "") + "\n";

        // Infoga ny address entry precis före </addresses>
        StringBuilder result = new StringBuilder();
        result.append(beforeInsert);
        result.append(indentedAddressEntry);
        result.append("\n");
        result.append(existingContent.substring(insertPosition));

        return result.toString();
    }

    /**
     * Detekterar indenteringen som används för <address> taggar i befintlig fil.
     */
    private String detectAddressIndent(String content) {
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "^([ \\t]*)<address\\s+name=",
                java.util.regex.Pattern.MULTILINE
        );
        java.util.regex.Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            return matcher.group(1);
        }

        // Fallback: använd 12 mellanslag om ingen indentering hittas
        return "            ";
    }

    /**
     * Applicerar indentering på address entry.
     * - address taggar: baseIndent
     * - anycast/multicast: baseIndent + 2
     * - queue: baseIndent + 4
     */
    private String applyAddressIndentation(String content, String baseIndent) {
        StringBuilder result = new StringBuilder();
        String normalizedContent = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalizedContent.split("\n");
        String innerIndent = baseIndent + "  ";      // För anycast/multicast
        String innerInnerIndent = baseIndent + "    "; // För queue

        for (int i = 0; i < lines.length; i++) {
            String trimmedLine = lines[i].trim();

            if (trimmedLine.isEmpty()) {
                result.append("\n");
                continue;
            }

            // Bestäm indentering baserat på elementtyp
            if (trimmedLine.startsWith("<address") || trimmedLine.startsWith("</address")) {
                result.append(baseIndent).append(trimmedLine);
            } else if (trimmedLine.startsWith("<anycast") || trimmedLine.startsWith("</anycast") ||
                    trimmedLine.startsWith("<multicast") || trimmedLine.startsWith("</multicast")) {
                result.append(innerIndent).append(trimmedLine);
            } else if (trimmedLine.startsWith("<queue")) {
                result.append(innerInnerIndent).append(trimmedLine);
            } else {
                result.append(innerIndent).append(trimmedLine);
            }

            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }
}