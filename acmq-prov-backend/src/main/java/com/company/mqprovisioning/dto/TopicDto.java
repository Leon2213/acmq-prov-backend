package com.company.mqprovisioning.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopicDto {

    private String id;
    private String name;
    private String environment;
    private String description;
    private String team;
    private String createdAt;
    private List<SubscriptionStatus> subscriptions;
    private List<String> producers;

    /**
     * Returns a summary version suitable for list views
     */
    public TopicSummary toSummary() {
        return TopicSummary.builder()
                .id(id)
                .name(name)
                .environment(environment)
                .team(team)
                .description(description)
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubscriptionStatus {
        private String name;
        private String subscriber;
        private boolean prodEnabled;
        private boolean testEnabled;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopicSummary {
        private String id;
        private String name;
        private String environment;
        private String team;
        private String description;
    }
}
