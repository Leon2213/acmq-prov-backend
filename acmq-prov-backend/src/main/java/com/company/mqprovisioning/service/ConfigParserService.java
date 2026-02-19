package com.company.mqprovisioning.service;

import com.company.mqprovisioning.dto.QueueDto;
import com.company.mqprovisioning.dto.TopicDto;
import com.company.mqprovisioning.dto.UserDto;
import com.company.mqprovisioning.service.git.GitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service för att läsa och parsa konfigurationsfiler från Git-repos.
 * Extraherar användare, köer, topics och deras relationer från:
 * - hieradata/role/acmq.yaml (användare och roller)
 * - puppet/modules/icc_artemis_broker/manifests/init.pp (köer)
 * - puppet/modules/icc_artemis_broker/templates/brokers/etc/broker.xml.erb (topics med producers/subscribers)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigParserService {

    private final GitService gitService;

    private static final String ACMQ_YAML_PATH = "role/acmq.yaml";
    private static final String INIT_PP_PATH = "modules/icc_artemis_broker/manifests/init.pp";
    private static final String BROKER_XML_PATH = "modules/icc_artemis_broker/templates/brokers/etc/broker.xml.erb";

    private static final String USERS_KEY = "icc_artemis_broker::artemis_users_properties_users";
    private static final String ROLES_KEY = "icc_artemis_broker::artemis_roles_properties_roles";

    /**
     * Läser in all data från konfigurationsfilerna.
     * Returnerar en ParsedConfig med användare, köer, topics och deras relationer.
     */
    public ParsedConfig parseAllConfigs() {
        log.info("Parsing all configuration files from Git repositories");

        try {
            // Förbered repos (clone/pull)
            gitService.prepareRepoHieradata();
            gitService.prepareRepoPuppet();

            // Läs filer
            String acmqYamlContent = gitService.readFile("hieradata", ACMQ_YAML_PATH);
            String brokerXmlContent = gitService.readFile("puppet", BROKER_XML_PATH);

            if (acmqYamlContent.isEmpty()) {
                log.warn("acmq.yaml is empty or not found");
            }
            if (brokerXmlContent.isEmpty()) {
                log.warn("broker.xml.erb is empty or not found");
            }

            // Parsa användare och roller från acmq.yaml
            List<String> userNames = parseUsers(acmqYamlContent);
            Map<String, List<String>> roleGroups = parseRoles(acmqYamlContent);

            // Parsa köer med producers och consumers från broker.xml.erb
            List<QueueDto> queues = parseQueuesFromBrokerXml(brokerXmlContent, roleGroups);

            // Parsa topics med producers och subscribers från broker.xml.erb
            List<TopicDto> topics = parseTopicsFromBrokerXml(brokerXmlContent, roleGroups);

            // Debug loggning
            log.info("Parsed {} users from acmq.yaml", userNames.size());
            log.info("Parsed {} role groups from acmq.yaml", roleGroups.size());
            log.info("Parsed {} queues from broker.xml.erb", queues.size());
            log.info("Parsed {} topics from broker.xml.erb", topics.size());

            // Bygg relationer mellan användare och resurser direkt från parsade köer och topics
            Map<String, UserRoles> userRolesMap = buildUserRolesFromResources(queues, topics);

            // Bygg DTO:er
            List<UserDto> users = buildUserDtos(userNames, userRolesMap);

            log.info("Final result: {} users, {} queues, {} topics", users.size(), queues.size(), topics.size());

            return new ParsedConfig(users, queues, topics);

        } catch (Exception e) {
            log.error("Error parsing configuration files", e);
            throw new RuntimeException("Kunde inte läsa konfigurationsfiler: " + e.getMessage(), e);
        }
    }

    /**
     * Extraherar användarlistan från acmq.yaml
     * Format:
     * icc_artemis_broker::artemis_users_properties_users:
     *   - 'admin'
     *   - 'test'
     *   - 'pratt-admin'
     */
    private List<String> parseUsers(String content) {
        List<String> users = new ArrayList<>();

        if (content == null || content.isEmpty()) {
            return users;
        }

        // Hitta början av users-sektionen
        int startIndex = content.indexOf(USERS_KEY + ":");
        if (startIndex == -1) {
            log.warn("Could not find {} in acmq.yaml", USERS_KEY);
            return users;
        }

        // Extrahera från början av sektionen till nästa tomma rad eller nästa sektion
        String fromSection = content.substring(startIndex);

        // Dela upp i rader och läs användare
        String[] lines = fromSection.split("\\r?\\n");
        boolean inUserSection = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // Första raden är headern
            if (trimmed.startsWith(USERS_KEY)) {
                inUserSection = true;
                continue;
            }

            // Tom rad eller ny sektion avslutar
            if (inUserSection) {
                if (trimmed.isEmpty() || (trimmed.contains("::") && trimmed.endsWith(":"))) {
                    break;
                }

                // Matcha format: - 'username'
                Pattern userPattern = Pattern.compile("^-\\s*'([^']*)'");
                Matcher matcher = userPattern.matcher(trimmed);
                if (matcher.find()) {
                    users.add(matcher.group(1));
                }
            }
        }

        log.info("Parsed {} users from acmq.yaml", users.size());
        return users;
    }

    /**
     * Extraherar roller (grupper) från acmq.yaml.
     * Format:
     * icc_artemis_broker::artemis_roles_properties_roles:
     *   - group: 'pensionsratt-admin'
     *     users: 'pratt-admin'
     *   - group: 'pensionsratt-read'
     *     users: 'pratt-ro,some-other-user'
     * Returnerar en map: gruppnamn -> lista med användare
     */
    private Map<String, List<String>> parseRoles(String content) {
        Map<String, List<String>> roles = new LinkedHashMap<>();

        if (content == null || content.isEmpty()) {
            return roles;
        }

        // Hitta början av roles-sektionen
        int startIndex = content.indexOf(ROLES_KEY + ":");
        if (startIndex == -1) {
            log.warn("Could not find {} in acmq.yaml", ROLES_KEY);
            return roles;
        }

        // Extrahera från början av sektionen
        String fromSection = content.substring(startIndex);
        String[] lines = fromSection.split("\\r?\\n");

        boolean inRolesSection = false;
        String currentGroup = null;

        for (String line : lines) {
            String trimmed = line.trim();

            // Första raden är headern
            if (trimmed.startsWith(ROLES_KEY)) {
                inRolesSection = true;
                continue;
            }

            if (inRolesSection) {
                // Tom rad eller ny sektion (som börjar med något::) avslutar
                if (trimmed.isEmpty() || (trimmed.contains("::") && trimmed.endsWith(":"))) {
                    break;
                }

                // Matcha group: 'gruppnamn'
                Pattern groupPattern = Pattern.compile("^-?\\s*group:\\s*'([^']*)'");
                Matcher groupMatcher = groupPattern.matcher(trimmed);
                if (groupMatcher.find()) {
                    currentGroup = groupMatcher.group(1);
                    continue;
                }

                // Matcha users: 'user1,user2'
                Pattern usersPattern = Pattern.compile("^users:\\s*'([^']*)'");
                Matcher usersMatcher = usersPattern.matcher(trimmed);
                if (usersMatcher.find() && currentGroup != null) {
                    String usersStr = usersMatcher.group(1);
                    List<String> usersList = Arrays.stream(usersStr.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.toList());
                    roles.put(currentGroup, usersList);
                    currentGroup = null;
                }
            }
        }

        log.info("Parsed {} role groups from acmq.yaml", roles.size());
        return roles;
    }

    /**
     * Parsar topics från broker.xml.erb baserat på security-settings.
     *
     * Topics identifieras genom security-settings med @address_*_topic_* i match-attributet.
     * Producers hittas från "send" permission roller.
     * Subscribers hittas från efterföljande security-settings med ::@multicast_* format.
     *
     * Exempel:
     * <security-setting match="<%= @address_paypay_topic_paypaysuppdrag_processed%>.#">
     *   <permission type="send" roles="paypay-admin,paypay-paypaysuppdrag"/>
     * </security-setting>
     * <security-setting match="<%= @address_paypay_topic_paypaysuppdrag_processed%>::<%= @multicast_processed_paypay_incidentprocess%>">
     *   <permission type="consume" roles="paypay-admin,paypay-incidentprocess"/>
     * </security-setting>
     */
    private List<TopicDto> parseTopicsFromBrokerXml(String content, Map<String, List<String>> roleGroups) {
        List<TopicDto> topics = new ArrayList<>();

        if (content == null || content.isEmpty()) {
            return topics;
        }

        // Steg 1: Hitta alla topic-definitioner (security-settings med _topic_ i address och .# i slutet)
        // Format: <security-setting match="<%= @address_xxx_topic_yyy%>.#">
        Pattern topicPattern = Pattern.compile(
                "<security-setting\\s+match=\"<%=\\s*@address_([a-zA-Z0-9_]*_topic_[a-zA-Z0-9_]*)\\s*%>\\.#\">(.*?)</security-setting>",
                Pattern.DOTALL
        );

        // Steg 2: Hitta subscription-definitioner för varje topic
        // Format: <security-setting match="<%= @address_topic_name%>::<%= @multicast_subscription_name%>">
        Pattern subscriptionPattern = Pattern.compile(
                "<security-setting\\s+match=\"<%=\\s*@address_([a-zA-Z0-9_]+)\\s*%>::<%=\\s*@multicast_([a-zA-Z0-9_]+)\\s*%>\">"
        );

        // Steg 3: Hitta send permission roller
        Pattern sendPermissionPattern = Pattern.compile(
                "<permission\\s+type=\"send\"\\s+roles=\"([^\"]+)\""
        );

        // Samla alla topics med deras data
        Map<String, TopicData> topicDataMap = new LinkedHashMap<>();

        // Hitta alla topics
        Matcher topicMatcher = topicPattern.matcher(content);
        while (topicMatcher.find()) {
            String topicVarName = topicMatcher.group(1); // t.ex. paypay_topic_paypaysuppdrag_processed
            String securitySettingContent = topicMatcher.group(2);

            // Hitta producers från send permission
            Set<String> producers = new LinkedHashSet<>();
            Matcher sendMatcher = sendPermissionPattern.matcher(securitySettingContent);
            if (sendMatcher.find()) {
                String rolesStr = sendMatcher.group(1);
                for (String role : rolesStr.split(",")) {
                    String trimmedRole = role.trim();
                    // Lägg till användare som har denna roll
                    if (roleGroups.containsKey(trimmedRole)) {
                        producers.addAll(roleGroups.get(trimmedRole));
                    } else {
                        // Om rollen inte finns i roleGroups, lägg till rollnamnet direkt
                        producers.add(trimmedRole);
                    }
                }
            }

            topicDataMap.put(topicVarName, new TopicData(topicVarName, producers));
        }

        // Hitta alla subscriptions och koppla till topics
        Matcher subscriptionMatcher = subscriptionPattern.matcher(content);
        while (subscriptionMatcher.find()) {
            String topicVarName = subscriptionMatcher.group(1);
            String subsrciptionNameRaw = subscriptionMatcher.group(2); // t.ex. "processed_subscription_incidentprocess" eller "processed_paypay_incidentprocess"
            String subscriptionName = subsrciptionNameRaw.replace("_", "-");

            if (topicDataMap.containsKey(topicVarName)) {
                // Extrahera subscription-namn med fallback-logik
                String subscriberNameRaw = extractSubscriptionName(subsrciptionNameRaw);
                String subscriberName = subscriberNameRaw.replace("_", "-");
                topicDataMap.get(topicVarName).subscriptions.put(subscriptionName, subscriberName);

                log.debug("Topic '{}': found subscriber '{}' from multicast '{}'", topicVarName, subscriberName, subscriptionName);
            }
        }

        // Bygg TopicDto:er
        int index = 1;
        for (Map.Entry<String, TopicData> entry : topicDataMap.entrySet()) {
            String topicVarName = entry.getKey();
            TopicData data = entry.getValue();

            // Konvertera variabelnamn till visningsnamn (ersätt _ med .)
            String displayName = topicVarName.replace("_", ".");

            TopicDto topic = TopicDto.builder()
                    .id("topic-" + index++)
                    .name(displayName)
                    .environment("prod")
                    .description("Artemis MQ Topic")
                    .team(extractTeamFromTopicName(topicVarName))
                    .createdAt(java.time.LocalDate.now().toString())
                    .producers(new ArrayList<>(data.producers))
                    .subscriptions(data.subscriptions != null ? new HashMap<>(data.subscriptions) : new HashMap<>())
                    .build();

            topics.add(topic);

            log.debug("Topic '{}': producers={}, subscribers={}", displayName, data.producers, data.subscribers);
        }

        return topics;
    }

    /**
     * Hjälpklass för att samla topic-data under parsning
     */
    private static class TopicData {
        String varName;
        Set<String> producers = new LinkedHashSet<>();
        Set<String> subscribers = new LinkedHashSet<>();
        Map<String, String> subscriptions = new HashMap<>();

        TopicData(String varName, Set<String> producers) {
            this.varName = varName;
            this.producers = producers;
        }
    }

    /**
     * Extraherar team-namn från topic-variabelnamn
     * t.ex. paypay_topic_paypaysuppdrag_processed -> Team Paypay
     */
    private String extractTeamFromTopicName(String varName) {
        // Första delen innan _topic_ är team-prefixet
        int topicIndex = varName.indexOf("_topic_");
        if (topicIndex > 0) {
            String prefix = varName.substring(0, topicIndex);
            return "Team " + prefix.substring(0, 1).toUpperCase() + prefix.substring(1);
        }
        return "Team Unknown";
    }

    /**
     * Extraherar subscription-namn från multicast-variabelnamn.
     *
     * Logik:
     * 1. Om variabelnamnet innehåller "_subscription_", ta allt efter det som subscription-namn
     * 2. Annars (fallback för felnamgivna), använd hela multicast-variabelnamnet
     *
     * Exempel:
     * - "processed_subscription_incidentprocess" -> "incidentprocess"
     * - "topic_subscription_mysubscriber" -> "mysubscriber"
     * - "processed_paypay_incidentprocess" (felnamngett) -> "processed_paypay_incidentprocess"
     *
     * @param multicastVarName variabelnamnet från @multicast_<namn>
     * @return subscription-namn att visa i frontend
     */
    private String extractSubscriptionName(String multicastVarName) {
        if (multicastVarName == null || multicastVarName.isEmpty()) {
            return multicastVarName;
        }

        // Leta efter "_subscription_" i variabelnamnet
        String subscriptionMarker = "_subscription_";
        int subscriptionIndex = multicastVarName.indexOf(subscriptionMarker);

        if (subscriptionIndex >= 0) {
            // Hittade "_subscription_" - ta allt efter det
            String subscriptionName = multicastVarName.substring(subscriptionIndex + subscriptionMarker.length());
            log.debug("Extracted subscription name '{}' from multicast var '{}' using _subscription_ marker",
                    subscriptionName, multicastVarName);
            return subscriptionName;
        }

        // Fallback: använd hela variabelnamnet (för felnamgivna)
        log.debug("No _subscription_ marker found in '{}', using full name as fallback", multicastVarName);
        return multicastVarName;
    }

    /**
     * Parsar köer från broker.xml.erb baserat på security-settings.
     *
     * Köer identifieras genom security-settings med @address_*_queue_* i match-attributet.
     * Producers hittas från "send" permission roller.
     * Consumers hittas från "consume" permission roller.
     *
     * Exempel:
     * <security-setting match="<%= @address_paypay_queue_paypaysarende%>.#">
     *   <permission type="send" roles="paypay-admin,paypay-producer"/>
     *   <permission type="consume" roles="paypay-admin,paypay-consumer"/>
     * </security-setting>
     */
    private List<QueueDto> parseQueuesFromBrokerXml(String content, Map<String, List<String>> roleGroups) {
        List<QueueDto> queues = new ArrayList<>();

        if (content == null || content.isEmpty()) {
            return queues;
        }

        // Hitta alla queue-definitioner (security-settings med _queue_ i address och .# i slutet)
        // Format: <security-setting match="<%= @address_xxx_queue_yyy%>.#">
        Pattern queuePattern = Pattern.compile(
                "<security-setting\\s+match=\"<%=\\s*@address_([a-zA-Z0-9_]*_queue_[a-zA-Z0-9_]*)\\s*%>\\.#\">(.*?)</security-setting>",
                Pattern.DOTALL
        );

        // Hitta send permission roller (producers)
        Pattern sendPermissionPattern = Pattern.compile(
                "<permission\\s+type=\"send\"\\s+roles=\"([^\"]+)\""
        );

        // Hitta consume permission roller (consumers)
        Pattern consumePermissionPattern = Pattern.compile(
                "<permission\\s+type=\"consume\"\\s+roles=\"([^\"]+)\""
        );

        // Samla alla köer med deras data
        Map<String, QueueData> queueDataMap = new LinkedHashMap<>();

        // Hitta alla köer
        Matcher queueMatcher = queuePattern.matcher(content);
        while (queueMatcher.find()) {
            String queueVarName = queueMatcher.group(1); // t.ex. paypay_queue_paypaysarende
            String securitySettingContent = queueMatcher.group(2);

            // Hitta producers från send permission
            Set<String> producers = new LinkedHashSet<>();
            Matcher sendMatcher = sendPermissionPattern.matcher(securitySettingContent);
            if (sendMatcher.find()) {
                String rolesStr = sendMatcher.group(1);
                for (String role : rolesStr.split(",")) {
                    String trimmedRole = role.trim();
                    // Lägg till användare som har denna roll
                    if (roleGroups.containsKey(trimmedRole)) {
                        producers.addAll(roleGroups.get(trimmedRole));
                    } else {
                        // Om rollen inte finns i roleGroups, lägg till rollnamnet direkt
                        producers.add(trimmedRole);
                    }
                }
            }

            // Hitta consumers från consume permission
            Set<String> consumers = new LinkedHashSet<>();
            Matcher consumeMatcher = consumePermissionPattern.matcher(securitySettingContent);
            if (consumeMatcher.find()) {
                String rolesStr = consumeMatcher.group(1);
                for (String role : rolesStr.split(",")) {
                    String trimmedRole = role.trim();
                    // Lägg till användare som har denna roll
                    if (roleGroups.containsKey(trimmedRole)) {
                        consumers.addAll(roleGroups.get(trimmedRole));
                    } else {
                        // Om rollen inte finns i roleGroups, lägg till rollnamnet direkt
                        consumers.add(trimmedRole);
                    }
                }
            }

            queueDataMap.put(queueVarName, new QueueData(queueVarName, producers, consumers));
        }

        // Bygg QueueDto:er
        int index = 1;
        for (Map.Entry<String, QueueData> entry : queueDataMap.entrySet()) {
            String queueVarName = entry.getKey();
            QueueData data = entry.getValue();

            // Konvertera variabelnamn till visningsnamn (ersätt _ med .)
            String displayName = queueVarName.replace("_", ".");

            QueueDto queue = QueueDto.builder()
                    .id("queue-" + index++)
                    .name(displayName)
                    .environment("prod")
                    .description("Artemis MQ Queue")
                    .team(extractTeamFromQueueName(queueVarName))
                    .createdAt(java.time.LocalDate.now().toString())
                    .producers(new ArrayList<>(data.producers))
                    .consumers(new ArrayList<>(data.consumers))
                    .build();

            queues.add(queue);

            log.debug("Queue '{}': producers={}, consumers={}", displayName, data.producers, data.consumers);
        }

        return queues;
    }

    /**
     * Hjälpklass för att samla queue-data under parsning
     */
    private static class QueueData {
        String varName;
        Set<String> producers = new LinkedHashSet<>();
        Set<String> consumers = new LinkedHashSet<>();

        QueueData(String varName, Set<String> producers, Set<String> consumers) {
            this.varName = varName;
            this.producers = producers;
            this.consumers = consumers;
        }
    }

    /**
     * Extraherar team-namn från queue-variabelnamn
     * t.ex. paypay_queue_paypaysarende -> Team Paypay
     */
    private String extractTeamFromQueueName(String varName) {
        // Första delen innan _queue_ är team-prefixet
        int queueIndex = varName.indexOf("_queue_");
        if (queueIndex > 0) {
            String prefix = varName.substring(0, queueIndex);
            return "Team " + prefix.substring(0, 1).toUpperCase() + prefix.substring(1);
        }
        return "Team Unknown";
    }

    /**
     * Bygger användarroller direkt från parsade köer och topics.
     * Producers och consumers är redan korrekt utlästa från broker.xml.erb:s
     * security-settings (send/consume permissions), så ingen extra hierdata-lookup behövs.
     */
    private Map<String, UserRoles> buildUserRolesFromResources(List<QueueDto> queues, List<TopicDto> topics) {
        Map<String, UserRoles> userRolesMap = new HashMap<>();

        for (QueueDto queue : queues) {
            if (queue.getProducers() != null) {
                for (String producer : queue.getProducers()) {
                    userRolesMap.computeIfAbsent(producer, k -> new UserRoles())
                            .producerQueues.add(queue.getName());
                }
            }
            if (queue.getConsumers() != null) {
                for (String consumer : queue.getConsumers()) {
                    userRolesMap.computeIfAbsent(consumer, k -> new UserRoles())
                            .consumerQueues.add(queue.getName());
                }
            }
        }

        for (TopicDto topic : topics) {
            if (topic.getProducers() != null) {
                for (String producer : topic.getProducers()) {
                    userRolesMap.computeIfAbsent(producer, k -> new UserRoles())
                            .producerTopics.add(topic.getName());
                }
            }
            if (topic.getSubscriptions() != null) {
                for (Map.Entry<String, String> sub : topic.getSubscriptions().entrySet()) {
                    String subscriptionName = sub.getKey();
                    String subscriber = sub.getValue();
                    userRolesMap.computeIfAbsent(subscriber, k -> new UserRoles())
                            .subscriptions.put(subscriptionName, topic.getName());
                }
            }
        }

        return userRolesMap;
    }

    /**
     * Bygger UserDto-objekt från parsad data
     */
    private List<UserDto> buildUserDtos(List<String> userNames, Map<String, UserRoles> userRolesMap) {
        List<UserDto> users = new ArrayList<>();

        for (int i = 0; i < userNames.size(); i++) {
            String userName = userNames.get(i);
            UserRoles roles = userRolesMap.getOrDefault(userName, new UserRoles());

            List<UserDto.RoleItem> producerRoles = new ArrayList<>();
            List<UserDto.RoleItem> consumerRoles = new ArrayList<>();
            List<UserDto.RoleItem> subscriptionRoles = new ArrayList<>();

            // Producer för köer
            for (String queue : roles.producerQueues) {
                producerRoles.add(UserDto.RoleItem.builder()
                        .type("queue")
                        .name(queue)
                        .environment("prod")
                        .build());
            }

            // Producer för topics
            for (String topic : roles.producerTopics) {
                producerRoles.add(UserDto.RoleItem.builder()
                        .type("topic")
                        .name(topic)
                        .environment("prod")
                        .build());
            }

            // Consumer för köer
            for (String queue : roles.consumerQueues) {
                consumerRoles.add(UserDto.RoleItem.builder()
                        .type("queue")
                        .name(queue)
                        .environment("prod")
                        .build());
            }

            // Subscriptions för topics
            for (Map.Entry<String, String> sub : roles.subscriptions.entrySet()) {
                subscriptionRoles.add(UserDto.RoleItem.builder()
                        .type("topic")
                        .name(sub.getValue())
                        .subscription(sub.getKey())
                        .environment("prod")
                        .build());
            }

            UserDto user = UserDto.builder()
                    .id("user-" + (i + 1))
                    .name(userName)
                    .description("Artemis MQ user")
                    .team(extractTeamFromName(userName))
                    .createdAt(java.time.LocalDate.now().toString())
                    .roles(UserDto.UserRoles.builder()
                            .producer(producerRoles)
                            .consumer(consumerRoles)
                            .subscription(subscriptionRoles)
                            .build())
                    .build();

            users.add(user);
        }

        return users;
    }


    /**
     * Försöker extrahera ett team-namn från användar/resursnamn
     */
    private String extractTeamFromName(String name) {
        // Enkel heuristik: använd första delen av namnet som team
        String prefix = extractPrefix(name);
        // Gör första bokstaven stor
        if (prefix.length() > 0) {
            return "Team " + prefix.substring(0, 1).toUpperCase() + prefix.substring(1);
        }
        return "Team Unknown";
    }

    /**
     * Extraherar prefix från ett namn (första delen före underscore eller punkt)
     */
    private String extractPrefix(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        // Försök hitta första delimitern (underscore eller punkt)
        int underscoreIdx = name.indexOf('_');
        int dotIdx = name.indexOf('.');

        int delimiterIdx = -1;
        if (underscoreIdx >= 0 && dotIdx >= 0) {
            delimiterIdx = Math.min(underscoreIdx, dotIdx);
        } else if (underscoreIdx >= 0) {
            delimiterIdx = underscoreIdx;
        } else if (dotIdx >= 0) {
            delimiterIdx = dotIdx;
        }

        if (delimiterIdx > 0) {
            return name.substring(0, delimiterIdx);
        }
        return name;
    }

    /**
     * Hjälpklass för att hålla användarroller temporärt
     */
    private static class UserRoles {
        Set<String> producerQueues = new LinkedHashSet<>();
        Set<String> consumerQueues = new LinkedHashSet<>();
        Set<String> producerTopics = new LinkedHashSet<>();
        // subscriptionName -> topicName
        Map<String, String> subscriptions = new LinkedHashMap<>();
    }

    /**
     * DTO för att returnera all parsad konfiguration
     */
    public static class ParsedConfig {
        public final List<UserDto> users;
        public final List<QueueDto> queues;
        public final List<TopicDto> topics;

        public ParsedConfig(List<UserDto> users, List<QueueDto> queues, List<TopicDto> topics) {
            this.users = users;
            this.queues = queues;
            this.topics = topics;
        }
    }
}