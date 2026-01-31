package com.company.mqprovisioning.service.template;

import com.company.mqprovisioning.dto.ProvisionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
public class HieradataTemplateService {

    public String generateRolesContent(ProvisionRequest request) {
        StringBuilder yaml = new StringBuilder();
        
        // Lägg till konsumenter
        if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            yaml.append(String.format("# Consumers for %s\n", request.getName()));
            for (String consumer : request.getConsumers()) {
                yaml.append(generateUserRole(consumer, "consumer", request));
            }
            yaml.append("\n");
        }
        
        // Lägg till producenter
        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            yaml.append(String.format("# Producers for %s\n", request.getName()));
            for (String producer : request.getProducers()) {
                yaml.append(generateUserRole(producer, "producer", request));
            }
            yaml.append("\n");
        }
        
        return yaml.toString();
    }

    private String generateUserRole(String username, String role, ProvisionRequest request) {
        StringBuilder yaml = new StringBuilder();
        
        yaml.append(String.format("%s_%s:\n", username, role));
        yaml.append("  users:\n");
        yaml.append(String.format("    - '%s'\n", username));
        yaml.append("  roles:\n");
        
        String permission;
        if ("consumer".equals(role)) {
            permission = "consume";
        } else {
            permission = "send";
        }
        
        yaml.append(String.format("    - name: '%s_%s_role'\n", request.getName(), role));
        yaml.append("      permissions:\n");
        yaml.append(String.format("        - type: '%s'\n", permission));
        yaml.append(String.format("          match: '%s'\n", request.getName()));
        yaml.append("\n");
        
        return yaml.toString();
    }

    public String generateAcmqContent(ProvisionRequest request) {
        StringBuilder yaml = new StringBuilder();
        
        if ("queue".equals(request.getResourceType())) {
            yaml.append(generateQueueDefinition(request));
        } else {
            yaml.append(generateTopicDefinition(request));
        }
        
        return yaml.toString();
    }

    private String generateQueueDefinition(ProvisionRequest request) {
        StringBuilder yaml = new StringBuilder();
        
        yaml.append("queues:\n");
        yaml.append(String.format("  - name: '%s'\n", request.getName()));

        if (request.getDescription() != null && !request.getDescription().isEmpty()) {
            yaml.append(String.format("    description: '%s'\n", 
                       request.getDescription().replace("'", "''")));
        }
        
        yaml.append("    durable: true\n");
        yaml.append("    routing_type: 'ANYCAST'\n");
        
        // Lägg till metadata
        yaml.append("    metadata:\n");
        yaml.append(String.format("      team: '%s'\n", request.getTeam()));
        yaml.append(String.format("      owner: '%s'\n", request.getRequester()));
        
        if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            yaml.append("      consumers:\n");
            for (String consumer : request.getConsumers()) {
                yaml.append(String.format("        - '%s'\n", consumer));
            }
        }
        
        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            yaml.append("      producers:\n");
            for (String producer : request.getProducers()) {
                yaml.append(String.format("        - '%s'\n", producer));
            }
        }
        
        return yaml.toString();
    }

    private String generateTopicDefinition(ProvisionRequest request) {
        StringBuilder yaml = new StringBuilder();
        
        yaml.append("topics:\n");
        yaml.append(String.format("  - name: '%s'\n", request.getName()));

        if (request.getDescription() != null && !request.getDescription().isEmpty()) {
            yaml.append(String.format("    description: '%s'\n", 
                       request.getDescription().replace("'", "''")));
        }
        
        yaml.append("    routing_type: 'MULTICAST'\n");
        
        // Lägg till metadata
        yaml.append("    metadata:\n");
        yaml.append(String.format("      team: '%s'\n", request.getTeam()));
        yaml.append(String.format("      owner: '%s'\n", request.getRequester()));
        
        if (request.getConsumers() != null && !request.getConsumers().isEmpty()) {
            yaml.append("      subscribers:\n");
            for (String consumer : request.getConsumers()) {
                yaml.append(String.format("        - '%s'\n", consumer));
            }
        }
        
        if (request.getProducers() != null && !request.getProducers().isEmpty()) {
            yaml.append("      publishers:\n");
            for (String producer : request.getProducers()) {
                yaml.append(String.format("        - '%s'\n", producer));
            }
        }
        
        return yaml.toString();
    }
}
