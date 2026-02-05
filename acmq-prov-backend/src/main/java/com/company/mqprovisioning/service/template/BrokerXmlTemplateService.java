package com.company.mqprovisioning.service.template;

import com.company.mqprovisioning.dto.ProvisionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Service för att generera broker.xml.erb security settings enligt Artemis-mönstret.
 *
 * Genererar två typer av security settings:
 * 1. Wildcard pattern för hela könamn-prefixet (t.ex. pensionsratt.#) - ENDAST om det inte redan finns
 * 2. Specifika settings för den exakta kön med ERB-variabel
 *
 * Scenarion:
 * - Nytt namespace: Skapar både namespace security-setting och kö/topic security-setting
 * - Befintligt namespace: Skapar endast kö/topic security-setting
 */
@Slf4j
@Service
public class BrokerXmlTemplateService {

    /**
     * Genererar security settings att lägga till i broker.xml.erb
     *
     * @param existingContent Befintligt innehåll i broker.xml.erb (för att kolla om namespace finns)
     * @param request Provisioning request med kö/topic-information
     * @return XML-sträng med security settings att lägga till
     */
    public String generateSecuritySettingsToAdd(String existingContent, ProvisionRequest request) {
        StringBuilder xml = new StringBuilder();

        String namespacePrefix = extractNamespacePrefix(request.getName());
        String variableName = convertToVariableName(request.getName());

        // 1. Kolla om namespace security setting redan finns
        boolean namespaceExists = checkNamespaceExists(existingContent, namespacePrefix);

        if (!namespaceExists) {
            // Namespace finns inte - skapa både namespace och resurs security-settings
            log.info("Namespace '{}' does not exist, creating namespace security-setting", namespacePrefix);
            xml.append(generateNamespaceSecuritySetting(namespacePrefix, request));
            xml.append("\n");
        } else {
            log.info("Namespace '{}' already exists, skipping namespace security-setting", namespacePrefix);
        }

        // 2. Kolla om resurs-specifik security setting redan finns
        boolean resourceExists = checkResourceSecuritySettingExists(existingContent, variableName);

        if (!resourceExists) {
            // 3. Generera specifik security setting för denna kö/topic med ERB-variabel
            xml.append(generateResourceSecuritySetting(variableName, namespacePrefix, request));
        } else {
            log.info("Resource security-setting for '{}' already exists, skipping creation", variableName);
        }

        return xml.toString().trim();
    }

    /**
     * Kontrollerar om ett namespace redan finns i broker.xml.erb
     */
    private boolean checkNamespaceExists(String existingContent, String namespacePrefix) {
        if (existingContent == null || existingContent.isEmpty()) {
            return false;
        }

        // Sök efter security-setting match="namespace.#" (wildcard pattern för namespace)
        String pattern = String.format("<security-setting\\s+match=\"%s\\.#\">", Pattern.quote(namespacePrefix));
        return Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(existingContent).find();
    }

    /**
     * Kontrollerar om en resurs-specifik security-setting redan finns i broker.xml.erb.
     * Söker efter mönstret: <security-setting match="<%= @address_variableName%>.#">
     */
    public boolean checkResourceSecuritySettingExists(String existingContent, String variableName) {
        if (existingContent == null || existingContent.isEmpty()) {
            return false;
        }

        // Sök efter security-setting med ERB-variabel för denna resurs
        // Pattern: <security-setting match="<%= @address_variableName%>.#">
        String pattern = String.format(
                "<security-setting\\s+match=\"<%%= @address_%s%%>\\.#\">",
                Pattern.quote(variableName)
        );
        boolean exists = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(existingContent).find();
        if (exists) {
            log.info("Resource security-setting for '{}' already exists in broker.xml.erb", variableName);
        }
        return exists;
    }

    /**
     * Uppdaterar en befintlig security-setting med nya producers/consumers.
     * Hittar den befintliga security-setting, parsar rollerna och ERSÄTTER dem.
     *
     * @param existingContent Befintligt innehåll i broker.xml.erb
     * @param request Provisioning request med nya producers/consumers
     * @return Uppdaterat innehåll med ersatta roller
     */
    public String updateExistingSecuritySetting(String existingContent, ProvisionRequest request) {
        String variableName = convertToVariableName(request.getName());

        // Hitta hela security-setting blocket för denna resurs
        // Pattern matchar: <security-setting match="<%= @address_xxx%>.#">...</security-setting>
        String securitySettingPattern = String.format(
                "(<security-setting\\s+match=\"<%%= @address_%s%%>\\.#\">)(.*?)(</security-setting>)",
                Pattern.quote(variableName)
        );

        java.util.regex.Pattern pattern = Pattern.compile(securitySettingPattern, Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(existingContent);

        if (!matcher.find()) {
            log.warn("Could not find security-setting for '{}' to update", variableName);
            return existingContent;
        }

        String openTag = matcher.group(1);
        String innerContent = matcher.group(2);
        String closeTag = matcher.group(3);

        // Uppdatera send permission med producers (ersätter befintliga)
        // Alltid anropa även om listan är tom - då behålls bara admin-rollen
        innerContent = replacePermissionRoles(innerContent, "send",
                request.getProducers() != null ? request.getProducers() : java.util.Collections.emptyList());

        // Uppdatera consume och browse permissions med consumers (ersätter befintliga)
        java.util.List<String> consumers = request.getConsumers() != null ? request.getConsumers() : java.util.Collections.emptyList();
        innerContent = replacePermissionRoles(innerContent, "consume", consumers);
        innerContent = replacePermissionRoles(innerContent, "browse", consumers);

        // Ersätt det gamla security-setting blocket med det uppdaterade
        String updatedSecuritySetting = openTag + innerContent + closeTag;
        String updatedContent = matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(updatedSecuritySetting));

        log.info("Updated security-setting for '{}' with new producers/consumers", variableName);
        return updatedContent;
    }

    /**
     * Ersätter roller för en specifik permission-typ.
     * Behåller admin-rollen och ersätter alla andra med de nya rollerna.
     */
    private String replacePermissionRoles(String innerContent, String permissionType, java.util.List<String> newRoles) {
        // Pattern för att hitta permission: <permission type="xxx" roles="role1,role2"/>
        String permissionPattern = String.format(
                "(<permission\\s+type=\"%s\"\\s+roles=\")([^\"]*)(\"\\s*/>)",
                Pattern.quote(permissionType)
        );

        java.util.regex.Pattern pattern = Pattern.compile(permissionPattern);
        java.util.regex.Matcher matcher = pattern.matcher(innerContent);

        if (!matcher.find()) {
            log.debug("Permission type '{}' not found, skipping", permissionType);
            return innerContent;
        }

        String prefix = matcher.group(1);
        String existingRoles = matcher.group(2);
        String suffix = matcher.group(3);

        // Hitta och behåll admin-rollen (den som slutar med -admin)
        String adminRole = null;
        if (existingRoles != null && !existingRoles.isEmpty()) {
            for (String role : existingRoles.split(",")) {
                String trimmedRole = role.trim();
                if (trimmedRole.endsWith("-admin")) {
                    adminRole = trimmedRole;
                    break;
                }
            }
        }

        // Bygg nya roller: admin-roll först, sedan de nya rollerna
        java.util.LinkedHashSet<String> roleSet = new java.util.LinkedHashSet<>();
        if (adminRole != null) {
            roleSet.add(adminRole);
        }

        // Lägg till nya roller (utan duplicering)
        for (String newRole : newRoles) {
            roleSet.add(newRole);
        }

        String updatedRoles = String.join(",", roleSet);
        String updatedPermission = prefix + updatedRoles + suffix;

        log.debug("Updated permission '{}': {} -> {}", permissionType, existingRoles, updatedRoles);

        return matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(updatedPermission));
    }

    /**
     * Returnerar variabelnamnet för en request (för användning i ProvisioningService)
     */
    public String getVariableName(ProvisionRequest request) {
        return convertToVariableName(request.getName());
    }

    /**
     * Kontrollerar om en subscription-specifik security-setting redan finns.
     * Söker efter mönstret: <security-setting match="<%= @address_xxx%>::<%= @multicast_yyy%>">
     */
    public boolean checkSubscriptionSecuritySettingExists(String existingContent, ProvisionRequest request) {
        if (existingContent == null || existingContent.isEmpty()) {
            return false;
        }

        if (request.getSubscriptionName() == null || request.getSubscriptionName().isEmpty()) {
            return true; // No subscription, so nothing to check
        }

        String variableName = convertToVariableName(request.getName());
        String subscriptionVarName = convertToVariableName(request.getSubscriptionName());

        // Pattern: <security-setting match="<%= @address_xxx%>::<%= @multicast_yyy%>">
        String pattern = String.format(
                "<security-setting\\s+match=\"<%%= @address_%s%%>::<%%= @multicast_%s%%>\">",
                Pattern.quote(variableName),
                Pattern.quote(subscriptionVarName)
        );
        boolean exists = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(existingContent).find();
        if (exists) {
            log.info("Subscription security-setting for '{}::{}' already exists", variableName, subscriptionVarName);
        }
        return exists;
    }

    /**
     * Genererar endast subscription security-setting för ett topic.
     * Används när topic redan finns men en ny subscription läggs till.
     */
    public String generateSubscriptionSecuritySetting(ProvisionRequest request) {
        if (request.getSubscriptionName() == null || request.getSubscriptionName().isEmpty()) {
            return "";
        }

        String variableName = convertToVariableName(request.getName());
        String subscriptionVarName = convertToVariableName(request.getSubscriptionName());
        String namespacePrefix = extractNamespacePrefix(request.getName());
        String adminRole = namespacePrefix + "-admin";

        StringBuilder xml = new StringBuilder();
        xml.append(String.format("<security-setting match=\"<%%= @address_%s%%>::<%%= @multicast_%s%%>\">\n",
                variableName, subscriptionVarName));

        // Consume: admin + consumers
        xml.append(String.format("<permission type=\"consume\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        xml.append("\"/>\n");

        // Browse: admin + consumers
        xml.append(String.format("<permission type=\"browse\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        xml.append("\"/>\n");

        xml.append("</security-setting>");

        return xml.toString();
    }

    private String generateSecuritySettings(ProvisionRequest request) {
        StringBuilder xml = new StringBuilder();

        String namespacePrefix = extractNamespacePrefix(request.getName());
        String variableName = convertToVariableName(request.getName());

        // 1. Generera wildcard security setting för hela prefixet (t.ex. pensionsratt.#)
        xml.append(generateNamespaceSecuritySetting(namespacePrefix, request));
        xml.append("\n");

        // 2. Generera specifik security setting för denna kö med ERB-variabel
        xml.append(generateResourceSecuritySetting(variableName, namespacePrefix, request));

        return xml.toString();
    }

    /**
     * Genererar namespace security-setting (wildcard pattern för hela namespace)
     * T.ex: <security-setting match="utbetalning.#">
     *
     * OBS: Genererar UTAN indentering - indentering läggs till av insertSecuritySettings
     */
    private String generateNamespaceSecuritySetting(String namespacePrefix, ProvisionRequest request) {
        StringBuilder xml = new StringBuilder();

        String adminRole = namespacePrefix + "-admin";
        String readRole = namespacePrefix + "-read";
        String writeRole = namespacePrefix + "-write";

        xml.append(String.format("<security-setting match=\"%s.#\">\n", namespacePrefix));
        xml.append(String.format("    <permission type=\"createNonDurableQueue\" roles=\"%s,%s,%s\"/>\n",
                adminRole, writeRole, readRole));
        xml.append(String.format("    <permission type=\"deleteNonDurableQueue\" roles=\"%s\"/>\n",
                adminRole));
        xml.append(String.format("    <permission type=\"createDurableQueue\" roles=\"%s,%s,%s\"/>\n",
                adminRole, writeRole, readRole));
        xml.append(String.format("    <permission type=\"deleteDurableQueue\" roles=\"%s\"/>\n",
                adminRole));
        xml.append(String.format("    <permission type=\"createAddress\" roles=\"%s,%s,%s\"/>\n",
                adminRole, writeRole, readRole));
        xml.append(String.format("    <permission type=\"deleteAddress\" roles=\"%s\"/>\n",
                adminRole));
        xml.append(String.format("    <permission type=\"consume\" roles=\"%s,%s\"/>\n",
                adminRole, readRole));
        xml.append(String.format("    <permission type=\"browse\" roles=\"%s,%s\"/>\n",
                adminRole, readRole));
        xml.append(String.format("    <permission type=\"send\" roles=\"%s,%s\"/>\n",
                adminRole, writeRole));
        xml.append("    <!-- we need this otherwise ./artemis data imp wouldn't work -->\n");
        xml.append(String.format("    <permission type=\"manage\" roles=\"%s\"/>\n",
                adminRole));
        xml.append("</security-setting>");

        return xml.toString();
    }

    /**
     * Genererar resurs-specifik security-setting (kö eller topic)
     * OBS: Genererar UTAN indentering - indentering läggs till av insertSecuritySettings
     */
    private String generateResourceSecuritySetting(String variableName, String namespacePrefix,
                                                   ProvisionRequest request) {
        if ("topic".equals(request.getResourceType())) {
            return generateTopicSecuritySettings(variableName, namespacePrefix, request);
        } else {
            return generateQueueSecuritySetting(variableName, namespacePrefix, request);
        }
    }

    /**
     * Genererar security-setting för queue (ANYCAST)
     */
    private String generateQueueSecuritySetting(String variableName, String namespacePrefix,
                                                ProvisionRequest request) {
        StringBuilder xml = new StringBuilder();
        String adminRole = namespacePrefix + "-admin";

        xml.append(String.format("<security-setting match=\"<%%= @address_%s%%>.#\">\n", variableName));

        // Send: admin + producers
        xml.append(String.format("<permission type=\"send\" roles=\"%s", adminRole));
        appendRoles(xml, request.getProducers());
        xml.append("\"/>\n");

        // Consume: admin + consumers
        xml.append(String.format("<permission type=\"consume\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        xml.append("\"/>\n");

        // Browse: admin + consumers
        xml.append(String.format("<permission type=\"browse\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        xml.append("\"/>\n");

        xml.append("</security-setting>");
        return xml.toString();
    }

    /**
     * Genererar security-settings för topic (MULTICAST)
     * Inkluderar:
     * 1. Huvudsaklig topic security-setting med alla permissions
     * 2. Subscription security-setting med :: pattern
     */
    private String generateTopicSecuritySettings(String variableName, String namespacePrefix,
                                                 ProvisionRequest request) {
        StringBuilder xml = new StringBuilder();
        String adminRole = namespacePrefix + "-admin";

        // 1. Huvudsaklig topic security-setting
        xml.append(String.format("<security-setting match=\"<%%= @address_%s%%>.#\">\n", variableName));

        // Send: admin + producers
        xml.append(String.format("<permission type=\"send\" roles=\"%s", adminRole));
        appendRoles(xml, request.getProducers());
        xml.append("\"/>\n");

        // Consume: admin + consumers
        xml.append(String.format("<permission type=\"consume\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        xml.append("\"/>\n");

        // Browse: admin + consumers
        xml.append(String.format("<permission type=\"browse\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        xml.append("\"/>\n");

        // CreateNonDurableQueue: admin + consumers + producers
        xml.append(String.format("<permission type=\"createNonDurableQueue\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        appendRoles(xml, request.getProducers());
        xml.append("\"/>\n");

        // DeleteNonDurableQueue: admin only
        xml.append(String.format("<permission type=\"deleteNonDurableQueue\" roles=\"%s\"/>\n", adminRole));

        // CreateDurableQueue: admin + consumers + producers
        xml.append(String.format("<permission type=\"createDurableQueue\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        appendRoles(xml, request.getProducers());
        xml.append("\"/>\n");

        // DeleteDurableQueue: admin only
        xml.append(String.format("<permission type=\"deleteDurableQueue\" roles=\"%s\"/>\n", adminRole));

        // CreateAddress: admin + consumers + producers
        xml.append(String.format("<permission type=\"createAddress\" roles=\"%s", adminRole));
        appendRoles(xml, request.getConsumers());
        appendRoles(xml, request.getProducers());
        xml.append("\"/>\n");

        // DeleteAddress: admin only
        xml.append(String.format("<permission type=\"deleteAddress\" roles=\"%s\"/>\n", adminRole));

        xml.append("</security-setting>");

        // 2. Subscription security-setting (om subscription finns)
        if (request.getSubscriptionName() != null && !request.getSubscriptionName().isEmpty()) {
            String subscriptionVarName = convertToVariableName(request.getSubscriptionName());

            xml.append("\n");
            xml.append(String.format("<security-setting match=\"<%%= @address_%s%%>::<%%= @multicast_%s%%>\">\n",
                    variableName, subscriptionVarName));

            // Consume: admin + consumers
            xml.append(String.format("<permission type=\"consume\" roles=\"%s", adminRole));
            appendRoles(xml, request.getConsumers());
            xml.append("\"/>\n");

            // Browse: admin + consumers
            xml.append(String.format("<permission type=\"browse\" roles=\"%s", adminRole));
            appendRoles(xml, request.getConsumers());
            xml.append("\"/>\n");

            xml.append("</security-setting>");
        }

        return xml.toString();
    }

    /**
     * Hjälpmetod för att lägga till roller i en permission-sträng
     */
    private void appendRoles(StringBuilder xml, java.util.List<String> roles) {
        if (roles != null && !roles.isEmpty()) {
            for (String role : roles) {
                xml.append(",").append(role);
            }
        }
    }

    /**
     * Extraherar namespace-prefix från resursnamnet.
     * T.ex: utbetalning.queue.utbetalningsuppdrag -> utbetalning
     */
    private String extractNamespacePrefix(String fullResourceName) {
        if (fullResourceName.contains(".")) {
            return fullResourceName.substring(0, fullResourceName.indexOf("."));
        }
        return fullResourceName;
    }

    private String convertToVariableName(String queueName) {
        // Konvertera punkter och bindestreck till underscore
        return queueName.replaceAll("[.\\-]", "_");
    }

    /**
     * Kontrollerar om en address entry redan finns i broker.xml.erb.
     * Söker efter mönstret: <address name="<%= @address_variableName%>">
     */
    public boolean checkAddressExists(String existingContent, ProvisionRequest request) {
        if (existingContent == null || existingContent.isEmpty()) {
            return false;
        }

        String variableName = convertToVariableName(request.getName());

        // Sök efter address med ERB-variabel för denna resurs
        // Pattern: <address name="<%= @address_variableName%>">
        String pattern = String.format(
                "<address\\s+name=\"<%%= @address_%s%%>\">",
                Pattern.quote(variableName)
        );
        boolean exists = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(existingContent).find();
        if (exists) {
            log.info("Address entry for '{}' already exists in broker.xml.erb", variableName);
        }
        return exists;
    }

    /**
     * Kontrollerar om en subscription queue redan finns i ett existerande address/multicast block.
     * Söker efter mönstret: <queue name="<%= @multicast_subscriptionVarName%>"/>
     */
    public boolean checkSubscriptionQueueExistsInAddress(String existingContent, ProvisionRequest request) {
        if (existingContent == null || existingContent.isEmpty()) {
            return false;
        }

        if (request.getSubscriptionName() == null || request.getSubscriptionName().isEmpty()) {
            return true; // No subscription, nothing to check
        }

        String subscriptionVarName = convertToVariableName(request.getSubscriptionName());

        // Pattern: <queue name="<%= @multicast_subscriptionVarName%>"/>
        String pattern = String.format(
                "<queue\\s+name=\"<%%= @multicast_%s%%>\"",
                Pattern.quote(subscriptionVarName)
        );
        boolean exists = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(existingContent).find();
        if (exists) {
            log.info("Subscription queue for '{}' already exists in address", subscriptionVarName);
        }
        return exists;
    }

    /**
     * Lägger till en subscription queue i ett existerande address/multicast block.
     * Hittar address-blocket och lägger till queue-taggen före </multicast>.
     */
    public String addSubscriptionQueueToExistingAddress(String existingContent, ProvisionRequest request) {
        if (request.getSubscriptionName() == null || request.getSubscriptionName().isEmpty()) {
            return existingContent;
        }

        String variableName = convertToVariableName(request.getName());
        String subscriptionVarName = convertToVariableName(request.getSubscriptionName());

        // Hitta address-blocket för detta topic
        // Pattern: <address name="<%= @address_xxx%>">...<multicast>...</multicast>...</address>
        String addressPattern = String.format(
                "(<address\\s+name=\"<%%= @address_%s%%>\">.*?<multicast>)(.*?)(</multicast>)",
                Pattern.quote(variableName)
        );

        java.util.regex.Pattern pattern = Pattern.compile(addressPattern, Pattern.DOTALL);
        java.util.regex.Matcher matcher = pattern.matcher(existingContent);

        if (!matcher.find()) {
            log.warn("Could not find address/multicast block for '{}' to add subscription queue", variableName);
            return existingContent;
        }

        String beforeMulticastContent = matcher.group(1);
        String multicastContent = matcher.group(2);
        String closeMulticast = matcher.group(3);

        // Detektera indentering från multicast-innehållet
        String queueIndent = "              "; // Default: 14 spaces
        java.util.regex.Pattern indentPattern = Pattern.compile("^(\\s*)<queue", Pattern.MULTILINE);
        java.util.regex.Matcher indentMatcher = indentPattern.matcher(multicastContent);
        if (indentMatcher.find()) {
            queueIndent = indentMatcher.group(1);
        }

        // Skapa ny queue-tagg
        String newQueueTag = String.format("%s<queue name=\"<%%= @multicast_%s%%>\"/>",
                queueIndent, subscriptionVarName);

        // Lägg till queue före </multicast>
        String trimmedMulticastContent = multicastContent.stripTrailing();
        String updatedMulticastContent = trimmedMulticastContent + "\n" + newQueueTag + "\n            ";

        // Ersätt i innehållet
        String updatedContent = matcher.replaceFirst(
                java.util.regex.Matcher.quoteReplacement(beforeMulticastContent + updatedMulticastContent + closeMulticast)
        );

        log.info("Added subscription queue '{}' to existing address '{}'", subscriptionVarName, variableName);
        return updatedContent;
    }

    /**
     * Genererar address entry att lägga till i <addresses> sektionen.
     *
     * För queue (anycast):
     * <address name="<%= @address_xxx %>">
     *   <anycast>
     *     <queue name="<%= @anycast_xxx %>"/>
     *   </anycast>
     * </address>
     *
     * För topic (multicast):
     * <address name="<%= @address_xxx %>">
     *   <multicast>
     *     <queue name="<%= @multicast_subscription_xxx %>"/>
     *   </multicast>
     * </address>
     *
     * OBS: Genererar UTAN indentering - indentering läggs till av insertAddress
     */
    public String generateAddressEntry(ProvisionRequest request) {
        StringBuilder xml = new StringBuilder();
        String variableName = convertToVariableName(request.getName());

        xml.append(String.format("<address name=\"<%%= @address_%s%%>\">\n", variableName));

        if ("queue".equals(request.getResourceType())) {
            // För ANYCAST (queue)
            xml.append("<anycast>\n");
            xml.append(String.format("<queue name=\"<%%= @anycast_%s%%>\"/>\n", variableName));
            xml.append("</anycast>\n");
        } else {
            // För MULTICAST (topic)
            xml.append("<multicast>\n");

            // Lägg till subscription queue om angiven
            if (request.getSubscriptionName() != null && !request.getSubscriptionName().isEmpty()) {
                String subscriptionVarName = convertToVariableName(request.getSubscriptionName());
                xml.append(String.format("<queue name=\"<%%= @multicast_%s%%>\"/>\n", subscriptionVarName));
            }

            xml.append("</multicast>\n");
        }

        xml.append("</address>");

        return xml.toString();
    }

}