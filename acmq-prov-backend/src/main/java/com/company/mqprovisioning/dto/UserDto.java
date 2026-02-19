package com.company.mqprovisioning.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {

    private String id;
    private String name;
    private String description;
    private String team;
    private String createdAt;
    private UserRoles roles;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserRoles {
        private List<RoleItem> producer;
        private List<RoleItem> consumer;
        private List<RoleItem> subscription;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RoleItem {
        private String type; // queue or topic
        private String name;
        private String subscription; // subscription name, only present for subscription role items
        private String environment;
    }

    /**
     * Returns a summary version suitable for list views
     */
    public UserSummary toSummary() {
        return UserSummary.builder()
                .id(id)
                .name(name)
                .team(team)
                .description(description)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserSummary {
        private String id;
        private String name;
        private String team;
        private String description;
    }
}
