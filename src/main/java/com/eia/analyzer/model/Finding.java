package com.eia.analyzer.model;

/**
 * A single problem detected in the analyzed source file.
 * Both the classic AST pass and the AI pass produce Findings,
 * so the comparative report can treat them uniformly.
 */
public class Finding {

    public enum Source { CLASSIC, AI }

    public enum Severity { INFO, WARNING, ERROR }

    private final int line;
    private final Severity severity;
    private final String type;
    private final String message;
    private final String suggestion;
    private final Source source;

    public Finding(int line, Severity severity, String type,
                   String message, String suggestion, Source source) {
        this.line = line;
        this.severity = severity;
        this.type = type;
        this.message = message;
        this.suggestion = suggestion;
        this.source = source;
    }

    public int getLine() {
        return line;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public String getSuggestion() {
        return suggestion;
    }

    public Source getSource() {
        return source;
    }

    @Override
    public String toString() {
        return String.format("[line %d] %s (%s): %s", line, type, severity, message);
    }
}
