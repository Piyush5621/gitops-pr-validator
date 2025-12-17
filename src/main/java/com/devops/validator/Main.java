package com.devops.validator;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Main {
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final Gson gson = new Gson();
    private static final HttpClient client = HttpClient.newHttpClient();

    private static String getEnv(String key) {
        String value = System.getenv(key);
        if (value == null) value = dotenv.get(key);
        return value;
    }

    public static void main(String[] args) {
        System.out.println("--- Advanced DevOps PR Validator Starting ---");

        String token = getEnv("GITHUB_TOKEN");
        String repoOwner = getEnv("REPO_OWNER");
        String repoName = getEnv("REPO_NAME");
        String prNumStr = getEnv("PR_NUMBER");
        int prNumber = (prNumStr != null) ? Integer.parseInt(prNumStr) : 1;

        if (token == null || token.isEmpty()) {
            System.err.println("❌ ERROR: GITHUB_TOKEN is missing.");
            System.exit(1);
        }

        System.out.println("Target: " + repoOwner + "/" + repoName + " | PR #" + prNumber);
        validatePullRequest(token, repoOwner, repoName, prNumber);
    }

    public static void validatePullRequest(String token, String owner, String repo, int prNumber) {
        try {
            // 1. Fetch Basic PR Metadata (Labels, Branch Name)
            PullRequest pr = fetchPRMetadata(token, owner, repo, prNumber);
            if (pr == null) return;

            // 2. Fetch List of Changed Files (For Test Coverage Check)
            List<String> changedFiles = fetchChangedFiles(token, owner, repo, prNumber);

            // 3. Fetch List of Commits (For Message Convention Check)
            List<String> commitMessages = fetchCommitMessages(token, owner, repo, prNumber);

            // 4. Run All Logic
            String feedback = runValidationChecks(pr, changedFiles, commitMessages);

            // 5. Post Feedback
            if (!feedback.isEmpty()) {
                System.out.println("⚠️ Issues found! Posting comment...");
                postComment(token, owner, repo, prNumber, feedback);
                System.exit(1); // Fail the build so the PR cannot be merged
            } else {
                System.out.println("✅ PR is perfect! No issues.");
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    // --- LOGIC SECTION ---

    public static String runValidationChecks(PullRequest pr, List<String> files, List<String> commits) {
        StringBuilder report = new StringBuilder();

        // CHECK 1: Branch Naming
        String branch = pr.getBranchName();
        if (!branch.startsWith("feature/") && !branch.startsWith("bugfix/") && !branch.startsWith("hotfix/")) {
            report.append("- ❌ **Invalid Branch Name**: `").append(branch).append("` must start with `feature/`, `bugfix/`, or `hotfix/`.\n");
        }

        // CHECK 2: Labels
        if (pr.getLabels() == null || pr.getLabels().isEmpty()) {
            report.append("- ❌ **Missing Labels**: Please add a label (e.g., `bug`, `feature`).\n");
        }

        // CHECK 3: Commit Message Conventions (NEW)
        // Regex looks for: "word: space message" (e.g., "feat: new login")
        Pattern commitPattern = Pattern.compile("^(feat|fix|docs|style|refactor|test|chore): .+$");
        for (String msg : commits) {
            if (!commitPattern.matcher(msg).matches()) {
                report.append("- ❌ **Bad Commit Message**: `").append(msg)
                      .append("` does not follow format `type: description` (e.g., `feat: add login`).\n");
            }
        }

        // CHECK 4: Missing Unit Tests (NEW)
        boolean hasJavaCode = false;
        boolean hasTests = false;

        for (String file : files) {
            if (file.endsWith(".java") && !file.endsWith("Test.java")) {
                hasJavaCode = true; // Found source code
            }
            if (file.endsWith("Test.java")) {
                hasTests = true; // Found a test file
            }
        }

        if (hasJavaCode && !hasTests) {
            report.append("- ❌ **Missing Unit Tests**: You modified Java code but didn't add any `Test.java` files. Quality first!\n");
        }

        // Formatting the final report
        if (report.length() > 0) {
            report.insert(0, "### 🛡️ GuardRails: PR Validation Report\n\n");
            report.append("\n_Please fix these issues to merge._");
        }

        return report.toString();
    }

    // --- API HELPER METHODS ---

    private static PullRequest fetchPRMetadata(String token, String owner, String repo, int prNumber) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        HttpResponse<String> response = sendRequest(url, token);
        if (response.statusCode() != 200) {
            System.err.println("Failed to fetch PR: " + response.statusCode());
            return null;
        }
        return gson.fromJson(response.body(), PullRequest.class);
    }

    private static List<String> fetchChangedFiles(String token, String owner, String repo, int prNumber) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/files";
        HttpResponse<String> response = sendRequest(url, token);
        List<String> fileNames = new ArrayList<>();
        
        if (response.statusCode() == 200) {
            JsonArray filesJson = gson.fromJson(response.body(), JsonArray.class);
            for (JsonElement file : filesJson) {
                fileNames.add(file.getAsJsonObject().get("filename").getAsString());
            }
        }
        return fileNames;
    }

    private static List<String> fetchCommitMessages(String token, String owner, String repo, int prNumber) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/commits";
        HttpResponse<String> response = sendRequest(url, token);
        List<String> messages = new ArrayList<>();

        if (response.statusCode() == 200) {
            JsonArray commitsJson = gson.fromJson(response.body(), JsonArray.class);
            for (JsonElement commitWrapper : commitsJson) {
                // GitHub JSON structure: [ { "commit": { "message": "..." } } ]
                JsonObject commitObj = commitWrapper.getAsJsonObject().get("commit").getAsJsonObject();
                messages.add(commitObj.get("message").getAsString());
            }
        }
        return messages;
    }

    private static void postComment(String token, String owner, String repo, int prNumber, String body) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/issues/" + prNumber + "/comments";
        JsonObject json = new JsonObject();
        json.addProperty("body", body);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(json)))
                .build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> sendRequest(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}