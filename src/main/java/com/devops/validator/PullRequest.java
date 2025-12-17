package com.devops.validator;

import java.util.List;

// This class maps the JSON response from GitHub to Java variables
public class PullRequest {
    private String title;
    private String body;
    private String state;
    private Head head; // "Head" contains branch info
    private List<Label> labels;

    // Getters
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getState() { return state; }
    public String getBranchName() { return head.ref; }
    public List<Label> getLabels() { return labels; }

    // Nested class for Branch info
    public static class Head {
        private String ref; // This is the branch name
    }

    // Nested class for Labels
    public static class Label {
        private String name;
        public String getName() { return name; }
    }
}