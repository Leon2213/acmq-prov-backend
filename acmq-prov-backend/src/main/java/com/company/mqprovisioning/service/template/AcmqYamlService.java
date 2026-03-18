package com.company.mqprovisioning.service.template;

import com.company.mqprovisioning.dto.ProvisionRequest;
import com.company.mqprovisioning.dto.SubscriptionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service för att uppdatera hieradata acmq.yaml filen med Artemis användare och grupper.
 * Bevarar hela filens struktur och ordning - uppdaterar endast roles och users sektionerna.
 * Följer mönstret:
 * icc_artemis_broker::artemis_users_properties_users - lista med alla användare
 * icc_artemis_broker::artemis_roles_properties_roles - grupper med användarmedlemskap
 */
@Slf4j
@Service
public class AcmqYamlService {

    private static final String USERS_KEY = "icc_artemis_broker::artemis_users_properties_users";
    private static final String ROLES_KEY = "icc_artemis_broker::artemis_roles_properties_roles";

    public String updateAcmqYaml(String existingContent, ProvisionRequest request) {
        log.info("Updating acmq.yaml for queue/topic: {}", request.getName());

        if (existingContent == null || existingContent.trim().isEmpty()) {
            throw new IllegalArgumentException("Existing acmq.yaml content cannot be empty");
        }

        // Extrahera nuvarande användare och roller från filen
        List<String> users = extractUsersList(existingContent);
        List<RoleEntry> roles = extractRolesList(existingContent);

        // Beräkna vilka nya användare och grupper som behövs
        String queuePrefix = extractQueuePrefix(request.getName());
        // Använd LinkedHashSet för att bevara insättningsordningen
        Set<String> newUsers = new LinkedHashSet<>();

        // Samla alla producenter och konsumenter (i den ordning de kommer)
        if (request.getProducers() != null) {
            newUsers.addAll(request.getProducers());
        }
        if (request.getConsumers() != null) {
            newUsers.addAll(request.getConsumers());
        }

        // Lägg till subscribers från nya subscriptions (behandlas som konsumenter)
        Set<String> subscribers = extractSubscribers(request);
        newUsers.addAll(subscribers);

        // Lägg till nya användare om de inte finns (behåll ordningen, lägg till sist)
        for (String user : newUsers) {
            if (!users.contains(user)) {
                users.add(user);
                log.info("Adding new user: {}", user);
            }
        }

        // Skapa/uppdatera grupper: könamn-admin, könamn-read, könamn-write
        updateRoleGroups(roles, queuePrefix, request, subscribers);

        // Skapa individuell grupp för varje ny användare: group: 'user', users: 'user'
        for (String user : newUsers) {
            updateOrCreateRole(roles, user, Collections.singleton(user));
        }

        // Ersätt users och roles sektionerna i original-innehållet
        String updatedContent = replaceUsersSection(existingContent, users);
        updatedContent = replaceRolesSection(updatedContent, roles);

        return updatedContent;
    }

    /**
     * Extraherar subscribers från subscriptions.
     * Använder ALLA subscriptions (isNew-flaggan är opålitlig från frontend).
     * Subscribers läggs till i både read- och write-gruppen.
     */
    private Set<String> extractSubscribers(ProvisionRequest request) {
        Set<String> subscribers = new LinkedHashSet<>();

        if (request.getSubscriptions() != null) {
            for (SubscriptionInfo subscription : request.getSubscriptions()) {
                if (subscription.getSubscriber() != null && !subscription.getSubscriber().isEmpty()) {
                    subscribers.add(subscription.getSubscriber());
                    log.info("Adding subscriber from subscription '{}': {}",
                            subscription.getSubscriptionName(), subscription.getSubscriber());
                }
            }
        }

        return subscribers;
    }

    /**
     * Inre klass för att representera en role-entry i YAML
     */
    private static class RoleEntry {
        String group;
        String users;

        RoleEntry(String group, String users) {
            this.group = group;
            this.users = users;
        }
    }

    /**
     * Extraherar användarlistan från acmq.yaml innehållet
     */
    private List<String> extractUsersList(String content) {
        List<String> users = new ArrayList<>();

        // Hitta sektionen team_artemis_broker::artemis_users_properties_users
        Pattern pattern = Pattern.compile(
                USERS_KEY + ":\\s*\\n((?:\\s+-\\s+'[^']*'\\s*\\n)+)",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String usersSection = matcher.group(1);
            // Extrahera varje användare (format: - 'username')
            Pattern userPattern = Pattern.compile("-\\s+'([^']*)'");
            Matcher userMatcher = userPattern.matcher(usersSection);

            while (userMatcher.find()) {
                users.add(userMatcher.group(1));
            }
        }

        log.info("Extracted {} users from acmq.yaml", users.size());
        return users;
    }

    /**
     * Extraherar roller från acmq.yaml innehållet
     */
    private List<RoleEntry> extractRolesList(String content) {
        List<RoleEntry> roles = new ArrayList<>();

        // Hitta sektionen team_artemis_broker::artemis_roles_properties_roles
        Pattern pattern = Pattern.compile(
                ROLES_KEY + ":\\s*\\n((?:\\s+-\\s+group:.*\\n\\s+users:.*\\n)+)",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String rolesSection = matcher.group(1);

            // Extrahera varje role entry (group + users par)
            Pattern rolePattern = Pattern.compile(
                    "-\\s+group:\\s+'([^']*)'\\s*\\n\\s+users:\\s+'([^']*)'",
                    Pattern.MULTILINE
            );
            Matcher roleMatcher = rolePattern.matcher(rolesSection);

            while (roleMatcher.find()) {
                String group = roleMatcher.group(1);
                String users = roleMatcher.group(2);
                roles.add(new RoleEntry(group, users));
            }
        }

        log.info("Extracted {} roles from acmq.yaml", roles.size());
        return roles;
    }

    /**
     * Ersätter users-sektionen i YAML-innehållet
     */
    private String replaceUsersSection(String content, List<String> users) {
        StringBuilder usersSection = new StringBuilder();
        usersSection.append(USERS_KEY).append(":\n");

        for (String user : users) {
            usersSection.append("  - '").append(user).append("'\n");
        }
        usersSection.append("\n");

        // Hitta och ersätt users-sektionen
        Pattern pattern = Pattern.compile(
                USERS_KEY + ":\\s*\\n(?:\\s+-\\s+'[^']*'\\s*\\n)+",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(usersSection.toString()));
        } else {
            // Om sektionen inte hittas, lägg till den i slutet (bör inte hända)
            log.warn("Users section not found in acmq.yaml, appending at the end");
            return content + "\n# artemis-broker users\n" + usersSection;
        }
    }

    /**
     * Ersätter roles-sektionen i YAML-innehållet
     */
    private String replaceRolesSection(String content, List<RoleEntry> roles) {
        StringBuilder rolesSection = new StringBuilder();
        rolesSection.append(ROLES_KEY).append(":\n");

        for (RoleEntry role : roles) {
            rolesSection.append("  - group: '").append(role.group).append("'\n");
            rolesSection.append("    users: '").append(role.users).append("'\n");
        }

        // Hitta och ersätt roles-sektionen
        Pattern pattern = Pattern.compile(
                ROLES_KEY + ":\\s*\\n(?:\\s+-\\s+group:.*\\n\\s+users:.*\\n)+",
                Pattern.MULTILINE
        );

        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(rolesSection.toString()));
        } else {
            // Om sektionen inte hittas, lägg till den i slutet (bör inte hända)
            log.warn("Roles section not found in acmq.yaml, appending at the end");
            return content + "\n# artemis-broker roles\n" + rolesSection;
        }
    }

    private String extractQueuePrefix(String fullQueueName) {
        // Om kön heter t.ex. "pensionsratt.queue.kontrakt.overforda.prepratter"
        // vill vi extrahera "pensionsratt" eller den första delen
        // För enkelhetens skull, använd hela namnet om det inte innehåller punkt,
        // annars använd första delen före första punkten
        if (fullQueueName.contains(".")) {
            return fullQueueName.substring(0, fullQueueName.indexOf("."));
        }
        return fullQueueName;
    }

    private void updateRoleGroups(List<RoleEntry> roles, String queuePrefix, ProvisionRequest request, Set<String> subscribers) {
        // Skapa eller uppdatera tre grupper: admin, read, write
        String adminGroup = queuePrefix + "-admin";
        String readGroup = queuePrefix + "-read";
        String writeGroup = queuePrefix + "-write";

        // Admin-grupp (alltid inkludera 'admin' användaren)
        updateOrCreateRole(roles, adminGroup, Collections.singleton("admin"));

        // Read-grupp (konsumenter + subscribers från subscriptions)
        Set<String> readUsers = new LinkedHashSet<>();
        if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            readUsers.addAll(request.getConsumers());
        }
        // Lägg till subscribers (de ska kunna läsa/konsumera från sina subscriptions)
        readUsers.addAll(subscribers);

        if (!readUsers.isEmpty()) {
            updateOrCreateRole(roles, readGroup, readUsers);
        }

        // Write-grupp (producenter + subscribers)
        Set<String> writeUsers = new LinkedHashSet<>();
        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            writeUsers.addAll(request.getProducers());
        }
        writeUsers.addAll(subscribers);

        if (!writeUsers.isEmpty()) {
            updateOrCreateRole(roles, writeGroup, writeUsers);
        }
    }

    /**
     * Uppdaterar enabled-flaggor för befintliga subscriptions i en YAML-fil.
     * Hanterar rader på formatet:
     *   icc_artemis_broker::multicast_<varName>_enabled: 'true'
     *
     * @param existingContent YAML-filens nuvarande innehåll
     * @param subscriptions   lista med subscription-uppdateringar (isNew=false filtreras ut)
     * @param useProdEnabled  true = använd prodEnabled-fältet, false = använd testEnabled-fältet
     */
    public String updateSubscriptionEnabledFlags(String existingContent, List<SubscriptionInfo> subscriptions, boolean useProdEnabled) {
        if (subscriptions == null || subscriptions.isEmpty() || existingContent == null) {
            return existingContent;
        }

        String content = existingContent;
        for (SubscriptionInfo sub : subscriptions) {
            Boolean explicitEnabled = useProdEnabled ? sub.getProdEnabled() : sub.getTestEnabled();

            // För befintliga subscriptions: hoppa över om ingen explicit ändring skickats
            if (!sub.isNew() && explicitEnabled == null) continue;

            // För nya subscriptions: default true om inget angivet (variabeln måste finnas i YAML
            // eftersom broker.xml.erb refererar till @multicast_xxx_enabled)
            boolean enabled = explicitEnabled != null ? explicitEnabled : true;

            String varName = resolveVarName(sub, content);
            if (varName == null) {
                log.warn("Kunde inte avgöra varName för subscription '{}', hoppar över enabled-uppdatering",
                        sub.isNew() ? sub.getSubscriptionName() : sub.getSubscriber());
                continue;
            }

            // Nya subscriptions skrivs alltid explicit (även true), befintliga bara om värdet ändras
            content = setEnabledFlag(content, varName, enabled, sub.isNew());
            log.info("{}subscription enabled flag: multicast_{}_enabled = {}",
                    sub.isNew() ? "New " : "Updated ", varName, enabled);
        }
        return content;
    }

    /**
     * Försöker avgöra subscriptionens variabelnamn (med underscores).
     * Försök 1: subscriptionName (display-namn med bindestreck → underscores).
     * Försök 2: sök i YAML efter befintlig enabled-rad som matchar subscriber-namnet.
     */
    private String resolveVarName(SubscriptionInfo sub, String content) {
        if (sub.getSubscriptionName() != null && !sub.getSubscriptionName().isEmpty()) {
            return sub.getSubscriptionName().replace("-", "_");
        }
        if (sub.getSubscriber() != null && !sub.getSubscriber().isEmpty()) {
            String subscriberVar = sub.getSubscriber().replace("-", "_");
            Pattern pattern = Pattern.compile(
                    "icc_artemis_broker::multicast_([a-zA-Z0-9_]+_subscription_" + Pattern.quote(subscriberVar) + ")_enabled"
            );
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /**
     * Sätter enabled-flaggan för en subscription i YAML-innehållet.
     * Om raden redan finns ersätts värdet. Om den inte finns och vi sätter false,
     * läggs raden till i slutet (true är default och behöver inte vara explicit).
     */
    private String setEnabledFlag(String content, String varName, boolean enabled) {
        return setEnabledFlag(content, varName, enabled, false);
    }

    /**
     * @param forceWrite true = skriv alltid raden explicit (t.ex. för nya subscriptions där
     *                   broker.xml.erb kräver att variabeln är definierad)
     */
    private String setEnabledFlag(String content, String varName, boolean enabled, boolean forceWrite) {
        String key = "icc_artemis_broker::multicast_" + varName + "_enabled";
        String newLine = key + ": '" + enabled + "'";

        Pattern pattern = Pattern.compile(
                "^" + Pattern.quote(key) + ":\\s*'(true|false)'",
                Pattern.MULTILINE
        );
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            return matcher.replaceFirst(Matcher.quoteReplacement(newLine));
        } else {
            // Skriv alltid explicit när nyckeln saknas – broker.xml.erb kräver att variabeln är definierad
            return content + (content.endsWith("\n") ? "" : "\n") + newLine + "\n";
        }
    }

    private void updateOrCreateRole(List<RoleEntry> roles, String groupName, Set<String> usersToAdd) {
        // Hitta befintlig grupp
        Optional<RoleEntry> existingRole = roles.stream()
                .filter(role -> groupName.equals(role.group))
                .findFirst();

        if (existingRole.isPresent()) {
            // Uppdatera befintlig grupp - BEHÅLL ORDNINGEN
            RoleEntry role = existingRole.get();
            String existingUsers = role.users;

            // Använd LinkedHashSet för att bevara ordning och undvika dubbletter
            LinkedHashSet<String> userSet = new LinkedHashSet<>();

            if (existingUsers != null && !existingUsers.isEmpty()) {
                // Lägg till befintliga användare först (behåller ordningen)
                userSet.addAll(Arrays.asList(existingUsers.split(",")));
            }

            // Lägg till nya användare (endast de som inte redan finns)
            userSet.addAll(usersToAdd);

            // Join UTAN sortering - behåller ordningen
            String updatedUsers = String.join(",", userSet);

            role.users = updatedUsers;
            log.info("Updated role group: {} with users: {}", groupName, updatedUsers);
        } else {
            // Skapa ny grupp - lägg till i slutet av listan
            String userList = String.join(",", usersToAdd);
            roles.add(new RoleEntry(groupName, userList));
            log.info("Created new role group: {} with users: {}", groupName, userList);
        }
    }

}