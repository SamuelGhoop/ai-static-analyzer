package com.eia.analyzer.report;

import com.eia.analyzer.model.Finding;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reproducibility experiment.
 *
 * The same file is analyzed N times by both passes. The classic pass acts as
 * the control group: it is deterministic by construction, so it must produce
 * an identical report every single run. Any instability observed in the AI
 * pass is therefore a property of the model, not of the harness.
 *
 * For each reported line we record which runs found it, and whether the label
 * the model attached to it stayed the same.
 */
public class VarianceReport {

    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String CYAN = "\u001B[36m";

    private static class Row {
        final Set<String> labels = new LinkedHashSet<>();
        final boolean[] present;

        Row(int runs) {
            this.present = new boolean[runs];
        }

        int timesSeen() {
            int count = 0;
            for (boolean seen : present) {
                if (seen) {
                    count++;
                }
            }
            return count;
        }
    }

    public void print(String fileName, int runs, Double temperature,
                      List<List<Finding>> classicRuns, List<List<Finding>> aiRuns) {

        System.out.println();
        System.out.println(BOLD + CYAN + "=".repeat(72) + RESET);
        System.out.println(BOLD + CYAN + "  REPRODUCIBILITY EXPERIMENT: " + fileName + RESET);
        System.out.println(BOLD + CYAN + "=".repeat(72) + RESET);
        System.out.printf("  Runs: %d    AI temperature: %s%n", runs,
                temperature == null ? "model default (not sent)" : String.valueOf(temperature));
        System.out.println(DIM
                + "  Same file, same prompt, same model. Only the engine differs."
                + RESET);

        double classicStability = printTable(BLUE, "CLASSIC PASS",
                "Deterministic AST rules -- the control group", runs, classicRuns);

        double aiStability = printTable(MAGENTA, "AI PASS",
                "LLM semantic review", runs, aiRuns);

        printVerdict(classicStability, aiStability);
    }

    /**
     * @return the percentage of distinct findings that appeared in every run.
     */
    private double printTable(String color, String title, String subtitle,
                              int runs, List<List<Finding>> allRuns) {

        Map<Integer, Row> rowsByLine = new TreeMap<>();

        for (int runIndex = 0; runIndex < allRuns.size(); runIndex++) {
            for (Finding finding : allRuns.get(runIndex)) {
                Row row = rowsByLine.computeIfAbsent(finding.getLine(), key -> new Row(runs));
                row.present[runIndex] = true;
                row.labels.add(finding.getType());
            }
        }

        System.out.println();
        System.out.println(BOLD + color + "-- " + title + RESET);
        System.out.println(DIM + "   " + subtitle + RESET);
        System.out.println();

        if (rowsByLine.isEmpty()) {
            System.out.println(DIM + "   (no findings)" + RESET);
            return 100.0;
        }

        StringBuilder head = new StringBuilder(String.format("   %-6s %-28s", "Line", "Label"));
        for (int i = 1; i <= runs; i++) {
            head.append(String.format(" %-3s", "R" + i));
        }
        head.append("  Seen");
        System.out.println(DIM + head + RESET);

        int stable = 0;
        for (Map.Entry<Integer, Row> entry : rowsByLine.entrySet()) {
            Row row = entry.getValue();
            int seen = row.timesSeen();
            if (seen == runs) {
                stable++;
            }

            String label = String.join(" / ", row.labels);
            boolean labelDrifted = row.labels.size() > 1;
            if (label.length() > 27) {
                label = label.substring(0, 24) + "...";
            }

            StringBuilder line = new StringBuilder(
                    String.format("   %-6d %-28s", entry.getKey(), label));
            for (boolean seenInRun : row.present) {
                line.append(seenInRun ? GREEN + " X  " + RESET : RED + " -  " + RESET);
            }

            String seenColor = (seen == runs) ? GREEN : YELLOW;
            line.append(String.format("  %s%d/%d%s", seenColor, seen, runs, RESET));
            if (labelDrifted) {
                line.append(YELLOW + "  <- label changed" + RESET);
            }
            System.out.println(line);
        }

        double stability = 100.0 * stable / rowsByLine.size();
        System.out.println();
        System.out.printf("   %sStability: %.0f%%%s  (%d of %d findings appeared in every run)%n",
                BOLD, stability, RESET, stable, rowsByLine.size());

        return stability;
    }

    private void printVerdict(double classicStability, double aiStability) {
        System.out.println();
        System.out.println(BOLD + CYAN + "-".repeat(72) + RESET);
        System.out.printf("  %-30s %s%.0f%%%s%n", "Classic pass reproducibility:",
                BLUE, classicStability, RESET);
        System.out.printf("  %-30s %s%.0f%%%s%n", "AI pass reproducibility:",
                MAGENTA, aiStability, RESET);
        System.out.println(BOLD + CYAN + "-".repeat(72) + RESET);
        System.out.println();

        if (aiStability < 100.0) {
            System.out.println(DIM
                    + "  The AI pass did not reproduce itself. Lowering the temperature"
                    + System.lineSeparator()
                    + "  reduces this, but does not remove it: batching and floating-point"
                    + System.lineSeparator()
                    + "  non-associativity on the GPU keep a residue of variance."
                    + RESET);
            System.out.println();
        }
    }
}