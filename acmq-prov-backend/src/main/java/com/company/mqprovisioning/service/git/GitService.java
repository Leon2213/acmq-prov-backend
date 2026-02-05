package com.company.mqprovisioning.service.git;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class GitService {

    @Autowired
    private BitbucketPRService bitbucketPRService;

    @Value("${git.hieradata.url}")
    private String hieradataRepoUrl;

    @Value("${git.puppet.url}")
    private String puppetRepoUrl;

    @Value("${git.username}")
    private String gitUsername;

    @Value("${git.bitbucket.token}")
    private String gitToken;

    @Value("${git.workspace.base:/tmp/mq-provisioning}")
    private String workspaceBase;

    private final Map<String, Git> repoCache = new HashMap<>();


    public void prepareRepoHieradata() {
        String repoName = "hieradata";
        try {
            Path repoPath = Paths.get(
                    System.getProperty("user.home"),
                    "repos",
                    "pmsrc",
                    "pup",
                    "hieradata"
            );

            if (Files.exists(repoPath)) {
                log.info("Repository {} already exists, pulling latest changes", repoName);

                Git git = Git.open(repoPath.toFile());

                StoredConfig config = git.getRepository().getConfig();
                config.setString("remote", "origin", "url", hieradataRepoUrl);
                config.save();
                resetIfBadState(git, repoName);

                // Checkout main branch before pulling to avoid issues with non-existent remote branches
                git.checkout()
                        .setName("master")
                        .call();

                git.fetch()
                        .setCredentialsProvider(getCredentialsProvider())
                        .call();
                git.reset()
                        .setMode(ResetCommand.ResetType.HARD)
                        .setRef("origin/master")
                        .call();

                repoCache.put(repoName, git);
            } else {
                log.info("Cloning repository {} from {}", repoName, hieradataRepoUrl);
                Files.createDirectories(repoPath);
                Git git = Git.cloneRepository()
                        .setURI(hieradataRepoUrl)
                        .setDirectory(repoPath.toFile())
                        .setCredentialsProvider(getCredentialsProvider())
                        .call();
                repoCache.put(repoName, git);
            }
        } catch (IOException | GitAPIException e) {
            log.error("Error preparing repository {}", repoName, e);
            throw new RuntimeException("Kunde inte förbereda repository: " + repoName, e);
        }
    }

    public void prepareRepoPuppet() {
            String repoName = "puppet";
            try {
                Path repoPath = Paths.get(
                        System.getProperty("user.home"),
                        "repos", "pmsrc", "pup", "puppet"
                );

                if (Files.exists(repoPath)) {
                    log.info("Repository {} already exists, syncing with remote", repoName);

                    Git git = Git.open(repoPath.toFile());

                    // Sätt korrekt remote URL
                    StoredConfig config = git.getRepository().getConfig();
                    config.setString("remote", "origin", "url", puppetRepoUrl);
                    config.save();

                    // Reset om bad state
                    resetIfBadState(git, repoName);

                    // Fetch och hard reset - ingen checkout behövs
                    git.fetch()
                            .setCredentialsProvider(getCredentialsProvider())
                            .call();

                    git.checkout()
                            .setName("prod")
                            .setForce(true)
                            .call();

                    git.reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef("origin/prod")
                            .call();

                    repoCache.put(repoName, git);
            } else {
                log.info("Cloning repository {} from {}", repoName, puppetRepoUrl);
                Files.createDirectories(repoPath);
                Git git = Git.cloneRepository()
                        .setURI(puppetRepoUrl)
                        .setDirectory(repoPath.toFile())
                        .setCredentialsProvider(getCredentialsProvider())
                        .call();
                repoCache.put(repoName, git);
            }
        } catch (IOException | GitAPIException e) {
            log.error("Error preparing repository {}", repoName, e);
            throw new RuntimeException("Kunde inte förbereda repository: " + repoName, e);
        }
    }

    public void createBranchHieradata(String branchName) {
        String repoName = "hieradata";
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            // Reset repository if it's in a bad state (MERGING, REBASING, etc.)
            resetIfBadState(git, repoName);

            // Checkout main först
            git.checkout()
                    .setName("master")
                    .call();

            // Pull senaste ändringarna
            git.fetch()
                    .setCredentialsProvider(getCredentialsProvider())
                    .call();
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/master")
                    .call();

            Path acmqPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), "role/acmq.yaml");
            log.info("Checking file at: {}", acmqPath);
            log.info("File exists: {}", Files.exists(acmqPath));
            if (Files.exists(acmqPath)) {
                log.info("File size: {} bytes", Files.size(acmqPath));
            }

            // Skapa och checkout ny branch
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .call();

            log.info("Created branch {} in repository {}", branchName, repoName);
        } catch (GitAPIException e) {
            log.error("Error creating branch {} in repository {}", branchName, repoName, e);
            throw new RuntimeException("Kunde inte skapa branch: " + branchName, e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void createBranchPuppet(String branchName) {
        String repoName = "puppet";
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            // Reset repository if it's in a bad state (MERGING, REBASING, etc.)
            resetIfBadState(git, repoName);

            // Checkout main först
            git.checkout()
                    .setName("prod")
                    .call();

            // Pull senaste ändringarna
            git.fetch()
                    .setCredentialsProvider(getCredentialsProvider())
                    .call();
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("origin/prod")
                    .call();

            // Skapa och checkout ny branch
            git.checkout()
                    .setCreateBranch(true)
                    .setName(branchName)
                    .call();

            log.info("Created branch {} in repository {}", branchName, repoName);
        } catch (GitAPIException e) {
            log.error("Error creating branch {} in repository {}", branchName, repoName, e);
            throw new RuntimeException("Kunde inte skapa branch: " + branchName, e);
        }
    }


    public void updateFile(String repoName, String filePath, String content) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path fullPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), filePath);

            // Läs befintlig fil om den finns
            String existingContent = "";
            if (Files.exists(fullPath)) {
                existingContent = Files.readString(fullPath);
            }

            // För ERB/XML filer, merga intelligent
            String mergedContent;
            if (filePath.endsWith(".erb") || filePath.endsWith(".xml")) {
                mergedContent = mergeXmlErbContent(existingContent, content);
            } else {
                // För andra filer (YAML etc), använd vanlig merge
                mergedContent = mergeContent(existingContent, content);
            }

            // Ensure parent directories exist
            if (fullPath.getParent() != null) {
                Files.createDirectories(fullPath.getParent());
            }

            Files.writeString(fullPath, mergedContent);

            git.add()
                    .addFilepattern(filePath)
                    .call();

            log.info("Updated file {} in repository {}", filePath, repoName);
        } catch (IOException | GitAPIException e) {
            log.error("Error updating file {} in repository {}", filePath, repoName, e);
            throw new RuntimeException("Kunde inte uppdatera fil: " + filePath, e);
        }
    }

    public void overwriteFile(String repoName, String filePath, String content) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path fullPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), filePath);

            // Ensure parent directories exist
            if (fullPath.getParent() != null) {
                Files.createDirectories(fullPath.getParent());
            }

            // Skriv över helt
            Files.writeString(fullPath, content);

            git.add()
                    .addFilepattern(filePath)
                    .call();

            log.info("Overwrote file {} in repository {}", filePath, repoName);
        } catch (IOException | GitAPIException e) {
            log.error("Error overwriting file {} in repository {}", filePath, repoName, e);
            throw new RuntimeException("Kunde inte skriva över fil: " + filePath, e);
        }
    }

    public String readFile(String repoName, String filePath) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            Path fullPath = Paths.get(git.getRepository().getWorkTree().getAbsolutePath(), filePath);

            if (Files.exists(fullPath)) {
                return Files.readString(fullPath);
            }

            return "";
        } catch (IOException e) {
            log.error("Error reading file {} in repository {}", filePath, repoName, e);
            throw new RuntimeException("Kunde inte läsa fil: " + filePath, e);
        }
    }

    public void commitAndPush(String repoName, String branchName, String commitMessage) {
        try {
            Git git = repoCache.get(repoName);
            if (git == null) {
                throw new IllegalStateException("Repository " + repoName + " inte förberedd");
            }

            git.commit()
                    .setMessage(commitMessage)
                    .call();

            git.push()
                    .setCredentialsProvider(getCredentialsProvider())
                    .setRemote("origin")
                    .add(branchName)
                    .call();

            log.info("Committed and pushed changes to branch {} in repository {}",
                    branchName, repoName);
        } catch (GitAPIException e) {
            log.error("Error committing and pushing to branch {} in repository {}",
                    branchName, repoName, e);
            throw new RuntimeException("Kunde inte commit och push: " + branchName, e);
        }
    }

    public String createPullRequest(String repoName, String sourceBranch,
                                    String targetBranch, String title, String description) {
        // Använd Bitbucket API för att skapa pull request
        return bitbucketPRService.createPullRequest(repoName, sourceBranch, targetBranch, title, description);
    }

    private String mergeContent(String existing, String newContent) {
        // Enkel implementation - använd ny content direkt (för YAML som hanteras av services)
        if (existing.isEmpty()) {
            return newContent;
        }
        // För YAML-filer hanteras merge i services (AcmqYamlService etc), så använd det nya
        return newContent;
    }

    private String mergeXmlErbContent(String existing, String newContent) {
        // Intelligent merge för XML/ERB filer
        if (existing.isEmpty()) {
            return newContent;
        }

        // Leta efter </security-settings> eller liknande closing tags
        // Infoga nytt innehåll INNAN closing tag
        if (existing.contains("</security-settings>")) {
            int insertPos = existing.indexOf("</security-settings>");
            StringBuilder result = new StringBuilder(existing);
            result.insert(insertPos, newContent + "\n");
            return result.toString();
        }

        // Om vi inte hittar rätt plats, lägg till i slutet
        log.warn("Could not find proper insertion point in XML/ERB, appending");
        return existing + "\n" + newContent;
    }

    /**
     * Reset repository if it's in a bad state (MERGING, REBASING, CHERRY_PICKING, etc.)
     * This can happen if a previous operation was interrupted.
     */
    private void resetIfBadState(Git git, String repoName) throws GitAPIException {
        RepositoryState state = git.getRepository().getRepositoryState();
        if (!state.canCommit()) {
            log.warn("Repository {} is in bad state: {}. Resetting to HEAD...", repoName, state);
            git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("HEAD")
                    .call();
            log.info("Repository {} reset successfully", repoName);
        }
    }

    private UsernamePasswordCredentialsProvider getCredentialsProvider() {
        return new UsernamePasswordCredentialsProvider(gitUsername, gitToken);
    }
}