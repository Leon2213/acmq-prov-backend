package com.company.mqprovisioning.service.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Bitbucket API Integration för att skapa Pull Requests
 *
 * Dokumentation: https://developer.atlassian.com/cloud/bitbucket/rest/
 */
@Slf4j
@Service
public class BitbucketPRService {

    @Value("${git.bitbucket.api.url:https://api.bitbucket.org/2.0}")
    private String bitbucketApiUrl;

    @Value("${git.bitbucket.token}")
    private String bitbucketToken;

    @Value("${git.bitbucket.workspace}")
    private String workspace;

    @Value("${git.bitbucket.hieradata.repo}")
    private String hieradataRepo;

    @Value("${git.bitbucket.puppet.repo}")
    private String puppetRepo;

    private final WebClient webClient;

    public BitbucketPRService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Skapa en Pull Request i Bitbucket
     */
    public String createPullRequest(String repoName, String sourceBranch,
                                   String targetBranch, String title, String description) {

        String repoSlug = getRepoSlug(repoName);

        log.info("Creating pull request in Bitbucket for {}/{} from {} to {}",
                workspace, repoSlug, sourceBranch, targetBranch);

        Map<String, Object> requestBody = Map.of(
                "title", title,
                "description", description,
                "source", Map.of(
                        "branch", Map.of("name", sourceBranch)
                ),
                "destination", Map.of(
                        "branch", Map.of("name", targetBranch)
                ),
                "close_source_branch", true
        );

//        try {
//            PullRequestResponse response = webClient.post()
//                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests",
//                         workspace, repoSlug)
//                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
//                    .bodyValue(requestBody)
//                    .retrieve()
//                    .bodyToMono(PullRequestResponse.class)
//                    .block();
//
//            if (response != null && response.getLinks() != null &&
//                response.getLinks().getHtml() != null) {
//                String prUrl = response.getLinks().getHtml().getHref();
//                log.info("Successfully created pull request #{}: {}", response.getId(), prUrl);
//                return prUrl;
//            } else {
//                throw new RuntimeException("Failed to create pull request - no response or URL");
//            }
//        } catch (Exception e) {
//            log.error("Error creating pull request in Bitbucket", e);
//            throw new RuntimeException("Failed to create pull request: " + e.getMessage(), e);
//        }
        return "prURL change later";
    }


    /**
     * Hämta PR status
     */
    public PullRequestStatus getPullRequestStatus(String repoName, int prId) {
        String repoSlug = getRepoSlug(repoName);

        try {
            PullRequestResponse response = webClient.get()
                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}",
                         workspace, repoSlug, prId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .retrieve()
                    .bodyToMono(PullRequestResponse.class)
                    .block();

            if (response != null) {
                return new PullRequestStatus(
                    response.getState(),
                    "MERGED".equals(response.getState()),
                    response.getLinks().getHtml().getHref()
                );
            }
            return null;
        } catch (Exception e) {
            log.error("Error getting pull request status", e);
            return null;
        }
    }

    /**
     * Lägg till en kommentar på PR
     */
    public void addComment(String repoName, int prId, String comment) {
        String repoSlug = getRepoSlug(repoName);

        try {
            webClient.post()
                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/comments",
                         workspace, repoSlug, prId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .bodyValue(Map.of("content", Map.of("raw", comment)))
                    .retrieve()
                    .bodyToMono(Void.class)
                    .block();

            log.info("Added comment to PR #{}", prId);
        } catch (Exception e) {
            log.warn("Failed to add comment: {}", e.getMessage());
        }
    }

    /**
     * Merge en PR (om godkänd)
     */
    public boolean mergePullRequest(String repoName, int prId, String commitMessage) {
        String repoSlug = getRepoSlug(repoName);

        try {
            Map<String, Object> mergeRequest = Map.of(
                "type", "pullrequest_merge",
                "message", commitMessage,
                "close_source_branch", true
            );

            webClient.post()
                    .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/merge",
                         workspace, repoSlug, prId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                    .bodyValue(mergeRequest)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            log.info("Successfully merged PR #{}", prId);
            return true;
        } catch (Exception e) {
            log.error("Failed to merge PR #{}: {}", prId, e.getMessage());
            return false;
        }
    }

    /**
     * Lägg till reviewers till PR
     */
    public void addReviewers(String repoName, int prId, List<String> reviewers) {
        String repoSlug = getRepoSlug(repoName);

        try {
            // Bitbucket kräver att varje reviewer läggs till individuellt
            for (String reviewer : reviewers) {
                webClient.put()
                        .uri(bitbucketApiUrl + "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/default-reviewers/{target_username}",
                             workspace, repoSlug, prId, reviewer)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bitbucketToken)
                        .retrieve()
                        .bodyToMono(Void.class)
                        .block();
            }

            log.info("Added reviewers {} to PR #{}", String.join(",", reviewers), prId);
        } catch (Exception e) {
            log.warn("Failed to add reviewers: {}", e.getMessage());
        }
    }

    private String getRepoSlug(String repoName) {
        return switch (repoName.toLowerCase()) {
            case "hieradata" -> hieradataRepo;
            case "puppet" -> puppetRepo;
            default -> throw new IllegalArgumentException("Unknown repository: " + repoName);
        };
    }

    // Response DTOs
    @lombok.Data
    public static class PullRequestResponse {
        private Integer id;
        private String title;
        private String description;
        private String state; // OPEN, MERGED, DECLINED, SUPERSEDED
        private Links links;
        private Source source;
        private Destination destination;

        @lombok.Data
        public static class Links {
            private Link html;
            private Link self;

            @lombok.Data
            public static class Link {
                private String href;
            }
        }

        @lombok.Data
        public static class Source {
            private Branch branch;
            private Repository repository;

            @lombok.Data
            public static class Branch {
                private String name;
            }

            @lombok.Data
            public static class Repository {
                private String name;
            }
        }

        @lombok.Data
        public static class Destination {
            private Branch branch;
            private Repository repository;

            @lombok.Data
            public static class Branch {
                private String name;
            }

            @lombok.Data
            public static class Repository {
                private String name;
            }
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class PullRequestStatus {
        private String state;
        private boolean merged;
        private String url;
    }
}
