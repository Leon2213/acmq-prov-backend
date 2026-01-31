package com.company.mqprovisioning.service;

import com.company.mqprovisioning.dto.QueueDto;
import com.company.mqprovisioning.dto.TopicDto;
import com.company.mqprovisioning.dto.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Service för att hantera resurser (användare, köer, topics).
 * Läser data från Git-repos via ConfigParserService.
 * Data cachas i minnet och kan uppdateras via refresh().
 */
@Slf4j
@Service
public class ResourceService {

    private final ConfigParserService configParserService;

    private final Map<String, UserDto> users = new ConcurrentHashMap<>();
    private final Map<String, QueueDto> queues = new ConcurrentHashMap<>();
    private final Map<String, TopicDto> topics = new ConcurrentHashMap<>();

    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private LocalDateTime lastRefresh;
    private String lastRefreshStatus = "Not initialized";

    @Autowired
    public ResourceService(@Lazy ConfigParserService configParserService) {
        this.configParserService = configParserService;
    }

    /**
     * Säkerställer att data är laddad. Anropas automatiskt vid första request.
     */
    private void ensureInitialized() {
        if (!initialized.get()) {
            synchronized (this) {
                if (!initialized.get()) {
                    refresh();
                }
            }
        }
    }

    /**
     * Laddar om all data från Git-repos.
     * Kan anropas för att uppdatera cachen.
     */
    public synchronized RefreshResult refresh() {
        log.info("Refreshing resource data from Git repositories");
        long startTime = System.currentTimeMillis();

        try {
            ConfigParserService.ParsedConfig config = configParserService.parseAllConfigs();

            // Rensa gammal data
            users.clear();
            queues.clear();
            topics.clear();

            // Lägg in ny data
            for (UserDto user : config.users) {
                users.put(user.getId(), user);
            }
            for (QueueDto queue : config.queues) {
                queues.put(queue.getId(), queue);
            }
            for (TopicDto topic : config.topics) {
                topics.put(topic.getId(), topic);
            }

            long duration = System.currentTimeMillis() - startTime;
            lastRefresh = LocalDateTime.now();
            lastRefreshStatus = String.format("Success: %d users, %d queues, %d topics loaded in %dms",
                    users.size(), queues.size(), topics.size(), duration);

            initialized.set(true);

            log.info("Resource data refreshed successfully: {} users, {} queues, {} topics in {}ms",
                    users.size(), queues.size(), topics.size(), duration);

            return new RefreshResult(true, lastRefreshStatus, lastRefresh,
                    users.size(), queues.size(), topics.size());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            lastRefreshStatus = "Error: " + e.getMessage();
            log.error("Failed to refresh resource data after {}ms", duration, e);

            // Om detta är första försöket och det misslyckas, initiera med tom data
            if (!initialized.get()) {
                initialized.set(true);
            }

            return new RefreshResult(false, lastRefreshStatus, lastRefresh,
                    users.size(), queues.size(), topics.size());
        }
    }

    /**
     * Returnerar status för senaste refresh
     */
    public RefreshResult getRefreshStatus() {
        return new RefreshResult(
                lastRefreshStatus != null && lastRefreshStatus.startsWith("Success"),
                lastRefreshStatus,
                lastRefresh,
                users.size(),
                queues.size(),
                topics.size()
        );
    }

    // ==================== Users ====================

    public List<UserDto.UserSummary> getAllUsers() {
        ensureInitialized();
        return users.values().stream()
                .map(UserDto::toSummary)
                .sorted(Comparator.comparing(UserDto.UserSummary::getName))
                .collect(Collectors.toList());
    }

    public Optional<UserDto> getUserById(String userId) {
        ensureInitialized();
        return Optional.ofNullable(users.get(userId));
    }

    public Optional<UserDto> getUserByName(String userName) {
        ensureInitialized();
        return users.values().stream()
                .filter(u -> u.getName().equals(userName))
                .findFirst();
    }

    // ==================== Queues ====================

    public List<QueueDto.QueueSummary> getAllQueues() {
        ensureInitialized();
        return queues.values().stream()
                .map(QueueDto::toSummary)
                .sorted(Comparator.comparing(QueueDto.QueueSummary::getName))
                .collect(Collectors.toList());
    }

    public Optional<QueueDto> getQueueById(String queueId) {
        ensureInitialized();
        return Optional.ofNullable(queues.get(queueId));
    }

    public Optional<QueueDto> getQueueByName(String queueName) {
        ensureInitialized();
        return queues.values().stream()
                .filter(q -> q.getName().equals(queueName))
                .findFirst();
    }

    // ==================== Topics ====================

    public List<TopicDto.TopicSummary> getAllTopics() {
        ensureInitialized();
        return topics.values().stream()
                .map(TopicDto::toSummary)
                .sorted(Comparator.comparing(TopicDto.TopicSummary::getName))
                .collect(Collectors.toList());
    }

    public Optional<TopicDto> getTopicById(String topicId) {
        ensureInitialized();
        return Optional.ofNullable(topics.get(topicId));
    }

    public Optional<TopicDto> getTopicByName(String topicName) {
        ensureInitialized();
        return topics.values().stream()
                .filter(t -> t.getName().equals(topicName))
                .findFirst();
    }

    // ==================== Result DTO ====================

    /**
     * Resultat från en refresh-operation
     */
    public static class RefreshResult {
        public final boolean success;
        public final String message;
        public final LocalDateTime timestamp;
        public final int userCount;
        public final int queueCount;
        public final int topicCount;

        public RefreshResult(boolean success, String message, LocalDateTime timestamp,
                             int userCount, int queueCount, int topicCount) {
            this.success = success;
            this.message = message;
            this.timestamp = timestamp;
            this.userCount = userCount;
            this.queueCount = queueCount;
            this.topicCount = topicCount;
        }
    }
}
