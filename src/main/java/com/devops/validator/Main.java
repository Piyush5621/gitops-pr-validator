package com.devops.validator;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.cdimascio.dotenv.Dotenv;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Main {
    // 1. Configure Dotenv to NOT crash if .env is missing (crucial for GitHub Actions)
    private static final Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
    private static final Gson gson = new Gson();
    private static final HttpClient client = HttpClient.newHttpClient();

    // 2. Helper method: Looks for variable in System (Cloud) first, then .env (Local)
    private static String getEnv(String key) {
        String value = System.getenv(key);
        if (value == null) {
            value = dotenv.get(key);
        }
        return value;
    }

    public static void main(String[] args) {
        System.out.println("--- DevOps PR Validator Starting ---");

        // 3. Load configuration using our robust helper
        String token = getEnv("GITHUB_TOKEN");
        String repoOwner = getEnv("REPO_OWNER");
        String repoName = getEnv("REPO_NAME");
        
        // 4. Dynamic PR Number: Try to get it from Env, default to 1 if missing
        String prNumStr = getEnv("PR_NUMBER");
        int prNumber = (prNumStr != null) ? Integer.parseInt(prNumStr) : 1;

        // Security Check
        if (token == null || token.isEmpty()) {
            System.out.println("❌ ERROR: GITHUB_TOKEN is missing. Check your .env file or GitHub Secrets.");
            System.exit(1); // Exit with error code so CI pipeline fails
        }

        System.out.println("Target Repository: " + repoOwner + "/" + repoName);
        System.out.println("Target PR Number: " + prNumber);

        fetchAndValidatePR(token, repoOwner, repoName, prNumber);
    }

    public static void fetchAndValidatePR(String token, String owner, String repo, int prNumber) {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                PullRequest pr = gson.fromJson(response.body(), PullRequest.class);
                System.out.println("✅ PR Fetched: " + pr.getTitle());

                String feedback = runValidationChecks(pr);

                if (!feedback.isEmpty()) {
                    System.out.println("⚠️ Issues found! Posting comment to GitHub...");
                    postComment(token, owner, repo, prNumber, feedback);
                    
                    // Optional: Fail the build if validation fails (Good for DevOps pipelines)
                    System.exit(1); 
                } else {
                    System.out.println("✅ PR looks good! No comment needed.");
                }

            } else {
                System.out.println("❌ Failed to fetch PR. Status: " + response.statusCode());
                System.out.println("Response: " + response.body());
                System.exit(1);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static String runValidationChecks(PullRequest pr) {
        StringBuilder report = new StringBuilder();

        // Check 1: Branch Name
        String branch = pr.getBranchName();
        if (!branch.startsWith("feature/") && !branch.startsWith("bugfix/") && !branch.startsWith("hotfix/")) {
            report.append("- ❌ **Invalid Branch Name**: `").append(branch)
                  .append("` must start with `feature/`, `bugfix/`, or `hotfix/`.\n");
        }

        // Check 2: Labels
        if (pr.getLabels() == null || pr.getLabels().isEmpty()) {
            report.append("- ❌ **Missing Labels**: Please add a label (e.g., `bug`, `feature`).\n");
        }

        if (report.length() > 0) {
            report.insert(0, "### 🤖 Automated PR Validator Report\n\n");
            report.append("\n_Please fix these issues to merge._");
        }

        return report.toString();
    }

    public static void postComment(String token, String owner, String repo, int prNumber, String commentBody) {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/issues/" + prNumber + "/comments";

        JsonObject json = new JsonObject();
        json.addProperty("body", commentBody);
        String jsonBody = gson.toJson(json);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 201) {
                System.out.println("✅ Comment posted successfully on GitHub!");
            } else {
                System.out.println("❌ Failed to post comment. Status: " + response.statusCode());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}