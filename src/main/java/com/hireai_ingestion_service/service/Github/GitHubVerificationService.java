package com.hireai_ingestion_service.service.Github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hireai_ingestion_service.dto.response.GitHubVerificationResult;
import com.hireai_ingestion_service.exception.ExternalServiceException;
import com.hireai_ingestion_service.exception.InvalidDocumentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Service
public class GitHubVerificationService {

    private static final Logger log = LoggerFactory.getLogger(GitHubVerificationService.class);
    private final RestClient restClient;

    public GitHubVerificationService(
            RestClient.Builder builder,
            @Value("${github.api.base-url:https://api.github.com}") String baseUrl,
            @Value("${github.api.token:}") String githubToken) {

        RestClient.Builder config = builder.baseUrl(baseUrl)
                .defaultHeader("Accept", "application/vnd.github.v3+json");

        if (githubToken != null && !githubToken.isBlank()) {
            config.defaultHeader("Authorization", "Bearer " + githubToken);
        }

        this.restClient = config.build();
    }

    public GitHubVerificationResult verifyCandidateProfile(String username) {
        if (username == null || username.trim().isEmpty()) {
            throw new InvalidDocumentException("GitHub username must not be empty.");
        }

        String cleanUsername = username.trim().replace("@", "");

        try {
            GitHubUserRecord response = restClient.get()
                    .uri("/users/{username}", cleanUsername)
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        if (res.getStatusCode().value() == 404) {
                            throw new InvalidDocumentException("GitHub handle '" + cleanUsername + "' does not exist.");
                        }
                        throw new ExternalServiceException("GitHub client validation error: " + res.getStatusText());
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ExternalServiceException("GitHub API is unreachable or experiencing upstream outages.");
                    })
                    .body(GitHubUserRecord.class);

            if (response == null) {
                throw new ExternalServiceException("Empty response payload received from GitHub identity service.");
            }

            if (response.publicRepos() == 0) {
                throw new InvalidDocumentException("GitHub account '%s' has 0 public repositories. Minimum required: 1."
                        .formatted(cleanUsername));
            }

            return new GitHubVerificationResult(
                    response.login(),
                    response.publicRepos(),
                    response.followers(),
                    response.htmlUrl(),
                    true
            );

        } catch (HttpClientErrorException.NotFound e) {
            throw new InvalidDocumentException("GitHub handle '" + cleanUsername + "' does not exist.");
        }
    }

    public record GitHubUserRecord(
            String login,
            @JsonProperty("public_repos") int publicRepos,
            int followers,
            @JsonProperty("html_url") String htmlUrl
    ) {}
}