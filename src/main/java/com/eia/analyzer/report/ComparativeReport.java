package com.eia.analyzer.report;

import com.eia.analyzer.model.Finding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pass 3: side-by-side comparison of both analyzers.
 *
 * Findings are joined by line number. That is a deliberate simplification, and
 * an honest one: two analyzers rarely phrase the same problem the same way,
 * and they do not always point at the same line either. When the classic rule
 * reports the line where a catch clause starts and the model reports the line
 * where its empty body sits, the same defect shows up twice as a
 * disagreement. Reconciling findings across a deterministic engine and a
 * probabilistic one is an open problem, not a detail.
 */
public class ComparativeReport {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";

    public void print(String fileName, List<Finding> classicFindings, List<Finding> aiFindings) {
        Set<Integer> classicLines = classicFindings.stream()
                .map(Finding::getLine)
                .collect(Collectors.toSet());
        Set<Integer> aiLines = aiFindings.stream()
                .map(Finding::getLine)
                .collect(Collectors.toSet());

        Set<Integer> sharedLines = new HashSet<>(classicLines);
        sharedLines.retainAll(aiLines);

        List<Finding> classicOnly = new ArrayList<>();
        for (Finding finding : classicFindings) {
            if (!sharedLines.contains(finding.getLine())) {
                classicOnly.add(finding);
            }
        }

        List<Finding> aiOnly = new ArrayList<>();
        for (Finding finding : aiFindings) {
            if (!sharedLines.contains(finding.getLine())) {
                aiOnly.add(finding);
            }
        }

        List<Integer> agreedLines = new ArrayList<>(sharedLines);
        Collections.sort(agreedLines);

        header("ANALYSIS REPORT: " + fileName);

        section(BLUE, "CLASSIC ONLY", pluralize(classicOnly.size(), "finding"),
                "Deterministic AST rules caught these. Fast, exact, reproducible.");
        printFindings(classicOnly);

        section(MAGENTA, "AI ONLY", pluralize(aiOnly.size(), "finding"),
                "Semantic problems no syntactic rule can express.");
        printFindings(aiOnly);

        section(GREEN, "BOTH AGREE", pluralize(agreedLines.size(), "line"),
                "Same line flagged by both passes: highest confidence.");
        printAgreedPairs(agreedLines, classicFindings, aiFindings);

        printSummary(classicFindings.size(), aiFindings.size(),
                classicOnly.size(), aiOnly.size(), agreedLines.size());
    }

    /**
     * Small formatting helper so the report reads "1 finding" instead of
     * "1 findings".
     */
    private String pluralize(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private void header(String title) {
        System.out.println();
        System.out.println(BOLD + CYAN + "=".repeat(72) + RESET);
        System.out.println(BOLD + CYAN + "  " + title + RESET);
        System.out.println(BOLD + CYAN + "=".repeat(72) + RESET);
    }

    private void section(String color, String title, String countLabel, String subtitle) {
        System.out.println();
        System.out.println(BOLD + color + "-- " + title + " (" + countLabel + ") " + RESET);
        System.out.println(DIM + "   " + subtitle + RESET);
        System.out.println();
    }

    private void printFindings(List<Finding> findings) {
        if (findings.isEmpty()) {
            System.out.println(DIM + "   (nothing)" + RESET);
            System.out.println();
            return;
        }
        findings.stream()
                .sorted((a, b) -> Integer.compare(a.getLine(), b.getLine()))
                .forEach(this::printFinding);
    }

    /**
     * Prints one block per agreed line, with both verdicts nested underneath,
     * so the reader can see how differently each engine describes the very
     * same defect.
     */
    private void printAgreedPairs(List<Integer> agreedLines,
                                  List<Finding> classicFindings,
                                  List<Finding> aiFindings) {
        if (agreedLines.isEmpty()) {
            System.out.println(DIM + "   (nothing)" + RESET);
            System.out.println();
            return;
        }

        for (int line : agreedLines) {
            System.out.println("   " + BOLD + "line " + line + RESET);
            for (Finding finding : classicFindings) {
                if (finding.getLine() == line) {
                    printPairedFinding(finding);
                }
            }
            for (Finding finding : aiFindings) {
                if (finding.getLine() == line) {
                    printPairedFinding(finding);
                }
            }
            System.out.println();
        }
    }

    private void printPairedFinding(Finding finding) {
        boolean isClassic = finding.getSource() == Finding.Source.CLASSIC;
        String sourceColor = isClassic ? BLUE : MAGENTA;
        String sourceLabel = isClassic ? "[classic]" : "[ai]";

        System.out.printf("      %s%-10s%s %s%-8s%s %s%s%s%n",
                sourceColor, sourceLabel, RESET,
                severityColor(finding.getSeverity()), finding.getSeverity(), RESET,
                BOLD, finding.getType(), RESET);
        System.out.println("         " + finding.getMessage());
        if (finding.getSuggestion() != null && !finding.getSuggestion().isBlank()) {
            System.out.println(DIM + "         fix: " + finding.getSuggestion() + RESET);
        }
    }

    private void printFinding(Finding finding) {
        System.out.printf("   %sline %-4d%s %s%-8s%s %s%s%s%n",
                BOLD, finding.getLine(), RESET,
                severityColor(finding.getSeverity()), finding.getSeverity(), RESET,
                BOLD, finding.getType(), RESET);
        System.out.println("      " + finding.getMessage());
        if (finding.getSuggestion() != null && !finding.getSuggestion().isBlank()) {
            System.out.println(DIM + "      fix: " + finding.getSuggestion() + RESET);
        }
        System.out.println();
    }

    private String severityColor(Finding.Severity severity) {
        return switch (severity) {
            case ERROR -> RED;
            case WARNING -> YELLOW;
            case INFO -> BLUE;
        };
    }

    private void printSummary(int classicTotal, int aiTotal,
                              int classicOnly, int aiOnly, int agreedLines) {
        System.out.println(BOLD + CYAN + "-".repeat(72) + RESET);
        System.out.printf("  %-30s %s%d%s%n", "Classic pass findings:", BLUE, classicTotal, RESET);
        System.out.printf("  %-30s %s%d%s%n", "AI pass findings:", MAGENTA, aiTotal, RESET);
        System.out.printf("  %-30s %d%n", "Found only by classic rules:", classicOnly);
        System.out.printf("  %-30s %d%n", "Found only by the AI:", aiOnly);
        System.out.printf("  %-30s %s%d%s%n", "Lines both passes agree on:", GREEN, agreedLines, RESET);
        System.out.println(BOLD + CYAN + "-".repeat(72) + RESET);
        System.out.println();
    }
}