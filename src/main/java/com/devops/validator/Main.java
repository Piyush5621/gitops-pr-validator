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
        String value = System.getenv(key); // Cloud/Docker env
        if (value == null) value = dotenv.get(key); // Local .env
        return value;
    }

    public static void main(String[] args) {
        System.out.println("--- 🛡️ DevSecOps Governance Platform Starting ---");

        String token = getEnv("GITHUB_TOKEN");
        String repoOwner = getEnv("REPO_OWNER");
        String repoName = getEnv("REPO_NAME");
        String prNumStr = getEnv("PR_NUMBER");
        int prNumber = (prNumStr != null) ? Integer.parseInt(prNumStr) : 1;

        if (token == null || token.isEmpty()) {
            System.err.println("❌ CRITICAL: GITHUB_TOKEN is missing. Shutting down.");
            System.exit(1);
        }

        System.out.println("🎯 Target: " + repoOwner + "/" + repoName + " | PR #" + prNumber);
        validatePullRequest(token, repoOwner, repoName, prNumber);
    }

    public static void validatePullRequest(String token, String owner, String repo, int prNumber) {
        try {
            // 1. Fetch Data
            PullRequest pr = fetchPRMetadata(token, owner, repo, prNumber);
            List<String> files = fetchChangedFiles(token, owner, repo, prNumber);
            List<String> commits = fetchCommitMessages(token, owner, repo, prNumber);

            // 2. Run Analysis (Logic)
            String feedback = runDevSecOpsChecks(pr, files, commits);
            
            // 3. Generate HTML Dashboard (Visual Report)
            generateHtmlDashboard(prNumber, repo, feedback);

            // 4. Take Action
            if (!feedback.isEmpty()) {
                System.out.println("⚠️ Quality Gate Failed! Posting feedback...");
                postComment(token, owner, repo, prNumber, feedback);
                
                // Optional: Send Discord Alert
                String discordUrl = getEnv("DISCORD_WEBHOOK");
                if (discordUrl != null) sendDiscordAlert(discordUrl, repo, prNumber, feedback);

                System.exit(1); // Fail the pipeline
            } else {
                System.out.println("✅ Quality Gate Passed! Code is secure.");
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    // --- 🧠 INTELLIGENT LOGIC ---

    public static String runDevSecOpsChecks(PullRequest pr, List<String> files, List<String> commits) {
        StringBuilder report = new StringBuilder();

        // 1. Branch Policy Check (Proposal 4)
        String branch = pr.getBranchName();
        if (!branch.startsWith("feature/") && !branch.startsWith("bugfix/") && !branch.startsWith("hotfix/")) {
            report.append("- ❌ **Governance**: Branch `").append(branch).append("` violates naming policy.\n");
        }

        // 2. Security Scan: Sensitive Files (Proposal 11)
        for (String file : files) {
            if (file.contains(".env") || file.contains("id_rsa") || file.contains("credentials.json")) {
                report.append("- 🚨 **SECURITY CRITICAL**: You committed a secret file (`").append(file).append("`)! **Immediate Action Required**.\n");
            }
        }

        // 3. Security Scan: Passwords in Commits (Proposal 11)
        for (String msg : commits) {
            String lowerMsg = msg.toLowerCase();
            if (lowerMsg.contains("password") || lowerMsg.contains("secret") || lowerMsg.contains("api_key")) {
                 report.append("- 🚨 **Security Warning**: Commit message contains sensitive words: `").append(msg).append("`.\n");
            }
        }

        // 4. Test Coverage Check (Quality)
        boolean hasJava = files.stream().anyMatch(f -> f.endsWith(".java"));
        boolean hasTest = files.stream().anyMatch(f -> f.endsWith("Test.java"));
        if (hasJava && !hasTest) {
            report.append("- ⚠️ **Quality Gap**: Java code modified without Unit Tests.\n");
        }

        // Format Report
        if (report.length() > 0) {
            report.insert(0, "### 🛡️ DevSecOps Automated Report\n\n**The following issues blocked the merge:**\n\n");
            report.append("\n_System: v2.0.1 (Dockerized)_");
        }
        return report.toString();
    }

    // --- 📊 VISUALIZATION ---
    
    public static void generateHtmlDashboard(int prNumber, String repo, String errors) {
        String status = errors.isEmpty() ? "PASSED" : "BLOCKED";
        String color = errors.isEmpty() ? "green" : "red";
        
        System.out.println("\n========== [ GENERATING HTML REPORT ] ==========");
        System.out.println("<html>");
        System.out.println("<body style='font-family: sans-serif; padding: 20px;'>");
        System.out.println("<h1 style='color:" + color + "'>Build Status: " + status + "</h1>");
        System.out.println("<h3>Repo: " + repo + " | PR: #" + prNumber + "</h3>");
        if (!errors.isEmpty()) {
            System.out.println("<div style='background: #ffe6e6; padding: 10px; border: 1px solid red;'>");
            System.out.println("<b>Issues Found:</b><br/>" + errors.replace("\n", "<br/>"));
            System.out.println("</div>");
        }
        System.out.println("</body></html>");
        System.out.println("================================================\n");
    }

    // --- 🔌 API CONNECTORS ---

    private static PullRequest fetchPRMetadata(String token, String owner, String repo, int prNumber) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber;
        HttpResponse<String> response = sendRequest(url, token);
        return gson.fromJson(response.body(), PullRequest.class);
    }

    private static List<String> fetchChangedFiles(String token, String owner, String repo, int prNumber) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/files";
        HttpResponse<String> response = sendRequest(url, token);
        List<String> fileNames = new ArrayList<>();
        if (response.statusCode() == 200) {
            JsonArray list = gson.fromJson(response.body(), JsonArray.class);
            for (JsonElement e : list) fileNames.add(e.getAsJsonObject().get("filename").getAsString());
        }
        return fileNames;
    }

    private static List<String> fetchCommitMessages(String token, String owner, String repo, int prNumber) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/commits";
        HttpResponse<String> response = sendRequest(url, token);
        List<String> msgs = new ArrayList<>();
        if (response.statusCode() == 200) {
            JsonArray list = gson.fromJson(response.body(), JsonArray.class);
            for (JsonElement e : list) msgs.add(e.getAsJsonObject().get("commit").getAsJsonObject().get("message").getAsString());
        }
        return msgs;
    }

    private static void postComment(String token, String owner, String repo, int prNumber, String body) throws Exception {
        String url = "https://api.github.com/repos/" + owner + "/" + repo + "/issues/" + prNumber + "/comments";
        JsonObject json = new JsonObject();
        json.addProperty("body", body);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(gson.toJson(json))).build();
        client.send(request, HttpResponse.BodyHandlers.ofString());
    }
    
    public static void sendDiscordAlert(String webhookUrl, String repo, int prNum, String errors) {
        String json = "{\"username\": \"DevSecOps Bot\", \"content\": \"🚨 **Security Gate Failed!**\\nRepo: " + repo + "\\nPR: " + prNum + "\"}";
        try {
             HttpRequest request = HttpRequest.newBuilder().uri(URI.create(webhookUrl)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
             client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) { System.err.println("Discord Error"); }
    }

    private static HttpResponse<String> sendRequest(String url, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Authorization", "Bearer " + token).GET().build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}