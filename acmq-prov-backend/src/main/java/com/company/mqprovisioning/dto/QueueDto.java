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
public class QueueDto {

    private String id;
    private String name;
    private String environment;
    private String description;
    private String team;
    private String createdAt;
    private List<String> consumers;
    private List<String> producers;

    /**
     * Returns a summary version suitable for list views
     */
    public QueueSummary toSummary() {
        return QueueSummary.builder()
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
    public static class QueueSummary {
        private String id;
        private String name;
        private String environment;
        private String team;
        private String description;
    }
}
