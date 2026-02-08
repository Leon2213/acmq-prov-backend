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

            // 2. Uppdatera Puppet broker.xml.erb repo
            log.info("Updating broker.xml.erb repository for request {}", requestId);
            String brokerXmlPR = updateBrokerXmlRepo(request, requestId);
            pullRequestUrls.add(brokerXmlPR);

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

    private String updateHieradataRepo(ProvisionRequest request, String requestId) {
        // Använd ärendenummer för branchnamn
        String branchName = String.format("feature/%s", request.getTicketNumber());

        // 1. Clone/pull hieradata repo
        gitService.prepareRepoHieradata();

        // 2. Läs befintlig acmq.yaml och beräkna uppdateringar
        String existingAcmqContent = gitService.readFile("hieradata", "role/acmq.yaml");

        // 2b. Uppdatera isNewSubscriber flaggan på subscriptions baserat på befintlig hieradata
        updateNewSubscriberFlags(request, existingAcmqContent);

        String updatedAcmqContent = acmqYamlService.updateAcmqYaml(existingAcmqContent, request);

        // 3. Kolla om det finns några ändringar - hoppa över PR om inga ändringar
        if (existingAcmqContent.equals(updatedAcmqContent)) {
            log.info("No changes needed in hieradata for request {} - all users/roles already exist", requestId);
            return null; // Ingen PR behövs
        }

        // 4. Skapa ny branch och genomför ändringar
        gitService.createBranchHieradata(branchName);
        gitService.overwriteFile("hieradata", "role/acmq.yaml", updatedAcmqContent);

        // 5. Commit och push
        String commitMessage = generateHieradataCommitMessage(request, existingAcmqContent);
        gitService.commitAndPush("hieradata", branchName, commitMessage);

        // 6. Skapa Pull Request
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

        // 4b. För topics med nya subscriptions: lägg till security-settings för varje ny subscription
        // Subscription security-settings infogas direkt efter topic security-setting (inte längst ner)
        if ("topic".equals(request.getResourceType()) && request.hasNewSubscriptions()) {
            for (SubscriptionInfo subscription : request.getNewSubscriptions()) {
                log.info("Processing new subscription: {} with subscriber: {}",
                        subscription.getSubscriptionName(), subscription.getSubscriber());

                // 1. Lägg till subscriber till topic:ets huvudsakliga security-setting
                //    (consume, browse, createNonDurableQueue, createDurableQueue, createAddress)
                if (subscription.getSubscriber() != null && !subscription.getSubscriber().isEmpty()) {
                    log.info("Adding subscriber '{}' to topic '{}' main security-setting",
                            subscription.getSubscriber(), request.getName());
                    updatedBrokerXml = brokerXmlTemplateService.addSubscriberToTopicSecuritySetting(
                            updatedBrokerXml, request.getName(), subscription.getSubscriber());
                }

                // 2. Lägg till subscription-specifik security-setting (topic::subscription pattern)
                if (!brokerXmlTemplateService.checkSubscriptionSecuritySettingExists(
                        updatedBrokerXml, request.getName(), subscription.getSubscriptionName())) {
                    log.info("Adding subscription security-setting for {}::{} after topic definition",
                            request.getName(), subscription.getSubscriptionName());
                    String subscriptionSecuritySetting = brokerXmlTemplateService.generateSubscriptionSecuritySetting(
                            request, subscription);
                    if (subscriptionSecuritySetting != null && !subscriptionSecuritySetting.trim().isEmpty()) {
                        // Infoga direkt efter topic security-setting istället för längst ner
                        updatedBrokerXml = brokerXmlTemplateService.insertSubscriptionSecuritySettingAfterTopic(
                                updatedBrokerXml, request.getName(), subscriptionSecuritySetting);
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
            // 5b. För topics med nya subscriptions: lägg till subscription queues i existerande address
            if ("topic".equals(request.getResourceType()) && request.hasNewSubscriptions()) {
                for (SubscriptionInfo subscription : request.getNewSubscriptions()) {
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

        // 5. Commit och push
        String commitMessage = generatePuppetCommitMessage(request, resourceSecurityExists);
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
        // Tillåt requests som har consumers, producers, eller nya subscriptions
        if (!request.hasConsumersOrProducers() && !request.hasNewSubscriptions()) {
            throw new IllegalArgumentException(
                    "Minst en konsument, producent eller ny subscription måste anges"
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

    /**
     * Genererar ett beskrivande commit-meddelande för hieradata (acmq.yaml).
     * Inkluderar information om beställare, team, och vad beställningen avser.
     * Detekterar automatiskt om subscribers är nya användare genom att kolla befintlig hieradata.
     */
    private String generateHieradataCommitMessage(ProvisionRequest request, String existingAcmqContent) {
        StringBuilder message = new StringBuilder();
        String resourceType = request.getResourceType();
        String resourceName = request.getName();
        boolean isNew = "new".equalsIgnoreCase(request.getRequestType());
        boolean hasNewSubscriptions = "topic".equals(resourceType) && request.hasNewSubscriptions();

        // Beställarinformation
        message.append(String.format("Beställare: %s\n", request.getRequester()));
        message.append(String.format("Team: %s\n", request.getTeam()));
        message.append(String.format("Ärende: %s\n", request.getTicketNumber()));

        // Beställning avser
        message.append("\nBeställning avser:\n");
        if (isNew) {
            message.append(String.format(" - Ny %s\n", resourceType));
        } else if (hasNewSubscriptions) {
            message.append(" - Ny subscription för befintlig topic\n");
        } else {
            message.append(String.format(" - Uppdatering av %s\n", resourceType));
        }

        // Resursinfo
        message.append(String.format("\n%s:\n",
                "topic".equals(resourceType) ? "Topic" : "Queue"));
        message.append(String.format(" - %s\n", resourceName));

        // Publishers (producers) - utan extra radbrytning före
        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            message.append("Publisher:\n");
            for (String producer : request.getProducers()) {
                message.append(String.format(" - %s\n", producer));
            }
        }

        // Consumers (om inte subscription-flow)
        if (!hasNewSubscriptions && request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            message.append("Consumer:\n");
            for (String consumer : request.getConsumers()) {
                message.append(String.format(" - %s\n", consumer));
            }
        }

        // Ändringar
        message.append("\nÄndring:\n");
        if (hasNewSubscriptions) {
            for (SubscriptionInfo sub : request.getNewSubscriptions()) {
                // Detektera automatiskt om subscribern är ny genom att kolla befintlig hieradata
                boolean isNewUser = !userExistsInHieradata(existingAcmqContent, sub.getSubscriber());
                String subscriberType = isNewUser ? "ny användare" : "existerande användare";
                message.append(String.format("Ny Subscription med %s som subscriber:\n", subscriberType));
                message.append(String.format(" - %s\n", sub.getSubscriptionName()));
                String userLabel = isNewUser ? "(new user)" : "(existing user)";
                message.append(String.format(" - %s %s\n", sub.getSubscriber(), userLabel));
            }
        } else {
            if (request.getProducers() != null && !request.getProducers().isEmpty()) {
                message.append("Nya producers:\n");
                for (String producer : request.getProducers()) {
                    message.append(String.format(" - %s\n", producer));
                }
            }
            if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
                message.append("Nya consumers:\n");
                for (String consumer : request.getConsumers()) {
                    message.append(String.format(" - %s\n", consumer));
                }
            }
        }

        return message.toString().trim();
    }

    /**
     * Uppdaterar isNewSubscriber flaggan på alla subscriptions baserat på om
     * subscribern redan finns i befintlig hieradata.
     */
    private void updateNewSubscriberFlags(ProvisionRequest request, String existingAcmqContent) {
        if (request.getSubscriptions() != null) {
            for (SubscriptionInfo sub : request.getSubscriptions()) {
                boolean isNewUser = !userExistsInHieradata(existingAcmqContent, sub.getSubscriber());
                sub.setNewSubscriber(isNewUser);
                log.debug("Subscriber '{}' is {} user", sub.getSubscriber(), isNewUser ? "new" : "existing");
            }
        }
    }

    /**
     * Kontrollerar om en användare redan finns definierad i hieradata (acmq.yaml).
     * Söker efter användaren i artemis_users_properties_users listan.
     */
    private boolean userExistsInHieradata(String acmqContent, String username) {
        if (acmqContent == null || username == null) {
            return false;
        }

        String quotedUsername = java.util.regex.Pattern.quote(username);

        // Matchar olika YAML-format för user i listan:
        // 1. - 'username' (enkla citattecken)
        // 2. - "username" (dubbla citattecken)
        // 3. - username (utan citattecken, följt av whitespace/radslut)
        String regex1 = "-\\s*'" + quotedUsername + "'";
        String regex2 = "-\\s*\"" + quotedUsername + "\"";
        String regex3 = "-\\s*" + quotedUsername + "(?:\\s|$|\\r?\\n)";

        java.util.regex.Pattern pattern1 = java.util.regex.Pattern.compile(regex1);
        java.util.regex.Pattern pattern2 = java.util.regex.Pattern.compile(regex2);
        java.util.regex.Pattern pattern3 = java.util.regex.Pattern.compile(regex3);

        boolean exists = pattern1.matcher(acmqContent).find() ||
                         pattern2.matcher(acmqContent).find() ||
                         pattern3.matcher(acmqContent).find();

        log.info("Checking if user '{}' exists in hieradata: {}", username, exists);
        return exists;
    }

    /**
     * Genererar ett beskrivande commit-meddelande för puppet (broker.xml.erb, init.pp).
     * Inkluderar information om beställare, team, och vad beställningen avser.
     */
    private String generatePuppetCommitMessage(ProvisionRequest request, boolean resourceSecurityExists) {
        StringBuilder message = new StringBuilder();
        String resourceType = request.getResourceType();
        String resourceName = request.getName();
        boolean isNew = "new".equalsIgnoreCase(request.getRequestType());
        boolean hasNewSubscriptions = "topic".equals(resourceType) && request.hasNewSubscriptions();

        // Beställarinformation
        message.append(String.format("Beställare: %s\n", request.getRequester()));
        message.append(String.format("Team: %s\n", request.getTeam()));
        message.append(String.format("Ärende: %s\n", request.getTicketNumber()));

        // Beställning avser
        message.append("\nBeställning avser:\n");
        if (isNew && !resourceSecurityExists) {
            message.append(String.format(" - Ny %s\n", resourceType));
        } else if (hasNewSubscriptions) {
            message.append(" - Ny subscription för befintlig topic\n");
        } else {
            message.append(String.format(" - Uppdatering av %s\n", resourceType));
        }

        // Resursinfo
        message.append(String.format("\n%s:\n",
                "topic".equals(resourceType) ? "Topic" : "Queue"));
        message.append(String.format(" - %s\n", resourceName));

        // Publishers (producers) - utan extra radbrytning före
        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            message.append("Publisher:\n");
            for (String producer : request.getProducers()) {
                message.append(String.format(" - %s\n", producer));
            }
        }

        // Consumers (om inte subscription-flow)
        if (!hasNewSubscriptions && request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            message.append("Consumer:\n");
            for (String consumer : request.getConsumers()) {
                message.append(String.format(" - %s\n", consumer));
            }
        }

        // Ändringar
        message.append("\nÄndring:\n");
        if (hasNewSubscriptions) {
            for (SubscriptionInfo sub : request.getNewSubscriptions()) {
                String subscriberType = sub.isNewSubscriber() ? "ny användare" : "existerande användare";
                message.append(String.format("Ny Subscription med %s som subscriber:\n", subscriberType));
                message.append(String.format(" - %s\n", sub.getSubscriptionName()));
                String userLabel = sub.isNewSubscriber() ? "(new user)" : "(existing user)";
                message.append(String.format(" - %s %s\n", sub.getSubscriber(), userLabel));
            }
        } else if (!isNew || resourceSecurityExists) {
            if (request.getProducers() != null && !request.getProducers().isEmpty()) {
                message.append("Nya producers:\n");
                for (String producer : request.getProducers()) {
                    message.append(String.format(" - %s\n", producer));
                }
            }
            if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
                message.append("Nya consumers:\n");
                for (String consumer : request.getConsumers()) {
                    message.append(String.format(" - %s\n", consumer));
                }
            }
        }

        return message.toString().trim();
    }
}