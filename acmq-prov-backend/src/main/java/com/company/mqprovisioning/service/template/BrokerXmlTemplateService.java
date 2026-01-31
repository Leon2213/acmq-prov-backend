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

        // 2. Generera specifik security setting för denna kö/topic med ERB-variabel
        xml.append(generateResourceSecuritySetting(variableName, namespacePrefix, request));

        return xml.toString();
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