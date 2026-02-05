package com.company.mqprovisioning.service.template;

import com.company.mqprovisioning.dto.ProvisionRequest;
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

        // Lägg till nya användare om de inte finns (behåll ordningen, lägg till sist)
        for (String user : newUsers) {
            if (!users.contains(user)) {
                users.add(user);
                log.info("Adding new user: {}", user);
            }
        }

        // Skapa/uppdatera grupper: könamn-admin, könamn-read, könamn-write
        updateRoleGroups(roles, queuePrefix, request);

        // Ersätt users och roles sektionerna i original-innehållet
        String updatedContent = replaceUsersSection(existingContent, users);
        updatedContent = replaceRolesSection(updatedContent, roles);

        return updatedContent;
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

    private void updateRoleGroups(List<RoleEntry> roles, String queuePrefix, ProvisionRequest request) {
        // Skapa eller uppdatera tre grupper: admin, read, write
        String adminGroup = queuePrefix + "-admin";
        String readGroup = queuePrefix + "-read";
        String writeGroup = queuePrefix + "-write";

        // Admin-grupp (alltid inkludera 'admin' användaren)
        updateOrCreateRole(roles, adminGroup, Collections.singleton("admin"));

        // Read-grupp (konsumenter)
        if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            updateOrCreateRole(roles, readGroup, new LinkedHashSet<>(request.getConsumers()));
        }

        // Write-grupp (producenter)
        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            updateOrCreateRole(roles, writeGroup, new LinkedHashSet<>(request.getProducers()));
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