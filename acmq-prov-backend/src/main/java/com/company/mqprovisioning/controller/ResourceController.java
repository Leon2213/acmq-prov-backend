package com.company.mqprovisioning.controller;

import com.company.mqprovisioning.dto.QueueDto;
import com.company.mqprovisioning.dto.TopicDto;
import com.company.mqprovisioning.dto.UserDto;
import com.company.mqprovisioning.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller för att exponera resurser (användare, köer, topics) till frontend.
 * Hanterar alla GET-endpoints för att lista och hämta enskilda resurser.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    // ==================== Users ====================

    @GetMapping("/users")
    public ResponseEntity<List<UserDto.UserSummary>> getAllUsers() {
        log.info("Fetching all users");
        List<UserDto.UserSummary> users = resourceService.getAllUsers();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/users/{userId}")
    public ResponseEntity<UserDto> getUserById(@PathVariable String userId) {
        log.info("Fetching user with id: {}", userId);
        return resourceService.getUserById(userId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Queues ====================

    @GetMapping("/queues")
    public ResponseEntity<List<QueueDto.QueueSummary>> getAllQueues() {
        log.info("Fetching all queues");
        List<QueueDto.QueueSummary> queues = resourceService.getAllQueues();
        return ResponseEntity.ok(queues);
    }

    @GetMapping("/queues/{queueId}")
    public ResponseEntity<QueueDto> getQueueById(@PathVariable String queueId) {
        log.info("Fetching queue with id: {}", queueId);
        return resourceService.getQueueById(queueId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Topics ====================

    @GetMapping("/topics")
    public ResponseEntity<List<TopicDto.TopicSummary>> getAllTopics() {
        log.info("Fetching all topics");
        List<TopicDto.TopicSummary> topics = resourceService.getAllTopics();
        return ResponseEntity.ok(topics);
    }

    @GetMapping("/topics/{topicId}")
    public ResponseEntity<TopicDto> getTopicById(@PathVariable String topicId) {
        log.info("Fetching topic with id: {}", topicId);
        return resourceService.getTopicById(topicId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // ==================== Refresh ====================

    /**
     * Triggar en uppdatering av all data från Git-repos.
     * Kan anropas av frontend för att säkerställa att senaste data visas.
     */
    @PostMapping("/refresh")
    public ResponseEntity<ResourceService.RefreshResult> refreshData() {
        log.info("Manual refresh triggered");
        ResourceService.RefreshResult result = resourceService.refresh();
        return ResponseEntity.ok(result);
    }

    /**
     * Hämtar status för senaste data-uppdatering.
     */
    @GetMapping("/refresh/status")
    public ResponseEntity<ResourceService.RefreshResult> getRefreshStatus() {
        return ResponseEntity.ok(resourceService.getRefreshStatus());
    }
}
