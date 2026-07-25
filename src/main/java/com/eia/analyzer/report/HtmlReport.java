package com.eia.analyzer.report;

import com.eia.analyzer.model.Finding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders the analysis as a self-contained HTML page.
 *
 * Console output is fine for a developer, but the comparison this project is
 * about -- two engines disagreeing, and one of them disagreeing with itself --
 * is much easier to read as a table you can look at all at once.
 *
 * The page has no external assets: one file, inline CSS, opens anywhere.
 */
public class HtmlReport {

    private static final Path OUTPUT_DIR = Path.of("report");
    private static final int MERGE_WINDOW = 1;

    // ---------------------------------------------------------------- public

    public Path writeAnalysis(String fileName, List<Finding> classicFindings,
                              List<Finding> aiFindings) throws IOException {

        Set<Integer> classicLines = classicFindings.stream()
                .map(Finding::getLine).collect(Collectors.toSet());
        Set<Integer> aiLines = aiFindings.stream()
                .map(Finding::getLine).collect(Collectors.toSet());
        Set<Integer> shared = new HashSet<>(classicLines);
        shared.retainAll(aiLines);

        List<Finding> classicOnly = classicFindings.stream()
                .filter(f -> !shared.contains(f.getLine())).toList();
        List<Finding> aiOnly = aiFindings.stream()
                .filter(f -> !shared.contains(f.getLine())).toList();

        StringBuilder body = new StringBuilder();

        body.append(statsRow(List.of(
                stat("Classic findings", String.valueOf(classicFindings.size()), "classic"),
                stat("AI findings", String.valueOf(aiFindings.size()), "ai"),
                stat("Only classic", String.valueOf(classicOnly.size()), "classic"),
                stat("Only AI", String.valueOf(aiOnly.size()), "ai"),
                stat("Lines both agree on", String.valueOf(shared.size()), "agree"))));

        body.append(section("classic", "Classic only",
                "Deterministic AST rules caught these. Fast, exact, reproducible.",
                findingsHtml(classicOnly)));

        body.append(section("ai", "AI only",
                "Semantic problems no syntactic rule can express.",
                findingsHtml(aiOnly)));

        StringBuilder agreed = new StringBuilder();
        List<Integer> sharedSorted = new ArrayList<>(shared);
        sharedSorted.sort(Integer::compare);
        if (sharedSorted.isEmpty()) {
            agreed.append("<p class=\"empty\">No line was flagged by both passes.</p>");
        }
        for (int line : sharedSorted) {
            agreed.append("<div class=\"pair\"><div class=\"pair-line\">line ")
                    .append(line).append("</div>");
            for (Finding f : classicFindings) {
                if (f.getLine() == line) {
                    agreed.append(findingHtml(f, true));
                }
            }
            for (Finding f : aiFindings) {
                if (f.getLine() == line) {
                    agreed.append(findingHtml(f, true));
                }
            }
            agreed.append("</div>");
        }
        body.append(section("agree", "Both agree",
                "Same line flagged by both passes: highest confidence.",
                agreed.toString()));

        return writePage("Analysis report", fileName, body.toString(),
                OUTPUT_DIR.resolve(fileName + ".analysis.html"));
    }

    public Path writeExperiment(String fileName, int runs,
                                List<List<Finding>> classicRuns,
                                List<List<Finding>> aiRuns) throws IOException {

        Grid classic = buildGrid(classicRuns, runs);
        Grid ai = buildGrid(aiRuns, runs);

        StringBuilder body = new StringBuilder();

        body.append(statsRow(List.of(
                stat("Runs", String.valueOf(runs), "neutral"),
                stat("Classic strict", pct(classic.strict), "classic"),
                stat("Classic merged", pct(classic.merged), "classic"),
                stat("AI strict", pct(ai.strict), "ai"),
                stat("AI merged", pct(ai.merged), "ai"))));

        body.append("<p class=\"note\">The classic pass is the control group: "
                + "deterministic by construction, it must reproduce itself exactly. "
                + "<strong>Strict</strong> groups findings by exact line. "
                + "<strong>Merged</strong> first fuses findings on adjacent lines into one "
                + "defect, so a bug the model located at line 75 on one run and line 76 on "
                + "the next is not punished twice. The gap between the two isolates how much "
                + "disagreement comes from not seeing a bug, versus not knowing where it is."
                + "</p>");

        body.append(section("classic", "Classic pass",
                "Deterministic AST rules -- the control group", gridHtml(classic, runs)));

        body.append(section("ai", "AI pass",
                "LLM semantic review", gridHtml(ai, runs)));

        return writePage("Reproducibility experiment", fileName, body.toString(),
                OUTPUT_DIR.resolve(fileName + ".experiment.html"));
    }

    // ----------------------------------------------------------- grid model

    private static class Grid {
        final Map<Integer, boolean[]> presence = new TreeMap<>();
        final Map<Integer, Set<String>> labels = new TreeMap<>();
        double strict;
        double merged;
        List<String> fused = new ArrayList<>();
    }

    private Grid buildGrid(List<List<Finding>> allRuns, int runs) {
        Grid grid = new Grid();

        for (int runIndex = 0; runIndex < allRuns.size(); runIndex++) {
            for (Finding finding : allRuns.get(runIndex)) {
                grid.presence
                        .computeIfAbsent(finding.getLine(), k -> new boolean[runs])[runIndex] = true;
                grid.labels
                        .computeIfAbsent(finding.getLine(), k -> new LinkedHashSet<>())
                        .add(finding.getType());
            }
        }

        int strictStable = 0;
        for (boolean[] row : grid.presence.values()) {
            if (countTrue(row) == runs) {
                strictStable++;
            }
        }
        grid.strict = grid.presence.isEmpty()
                ? 100.0 : 100.0 * strictStable / grid.presence.size();

        // Fuse findings on adjacent lines into a single defect.
        List<boolean[]> clusters = new ArrayList<>();
        List<List<Integer>> clusterLines = new ArrayList<>();
        boolean[] current = null;
        List<Integer> currentLines = null;
        int previous = 0;

        for (Map.Entry<Integer, boolean[]> entry : grid.presence.entrySet()) {
            int line = entry.getKey();
            if (current == null || line - previous > MERGE_WINDOW) {
                current = new boolean[runs];
                currentLines = new ArrayList<>();
                clusters.add(current);
                clusterLines.add(currentLines);
            }
            currentLines.add(line);
            for (int i = 0; i < runs; i++) {
                if (entry.getValue()[i]) {
                    current[i] = true;
                }
            }
            previous = line;
        }

        int mergedStable = 0;
        for (int i = 0; i < clusters.size(); i++) {
            if (countTrue(clusters.get(i)) == runs) {
                mergedStable++;
            }
            if (clusterLines.get(i).size() > 1) {
                grid.fused.add(clusterLines.get(i).stream()
                        .map(String::valueOf).collect(Collectors.joining("+")));
            }
        }
        grid.merged = clusters.isEmpty() ? 100.0 : 100.0 * mergedStable / clusters.size();

        return grid;
    }

    private int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------ rendering

    private String gridHtml(Grid grid, int runs) {
        if (grid.presence.isEmpty()) {
            return "<p class=\"empty\">No findings.</p>";
        }

        StringBuilder html = new StringBuilder("<table class=\"grid\"><thead><tr>");
        html.append("<th>Line</th><th>Label</th>");
        for (int i = 1; i <= runs; i++) {
            html.append("<th class=\"run\">R").append(i).append("</th>");
        }
        html.append("<th>Seen</th></tr></thead><tbody>");

        for (Map.Entry<Integer, boolean[]> entry : grid.presence.entrySet()) {
            boolean[] row = entry.getValue();
            int seen = countTrue(row);
            Set<String> labels = grid.labels.get(entry.getKey());
            boolean drifted = labels.size() > 1;

            html.append("<tr><td class=\"line\">").append(entry.getKey()).append("</td>");
            html.append("<td class=\"label\">").append(escape(String.join(" / ", labels)));
            if (drifted) {
                html.append(" <span class=\"drift\">label changed</span>");
            }
            html.append("</td>");

            for (boolean seenInRun : row) {
                html.append(seenInRun
                        ? "<td class=\"cell hit\">&#10003;</td>"
                        : "<td class=\"cell miss\">&#8212;</td>");
            }

            html.append("<td class=\"seen ").append(seen == runs ? "full" : "partial")
                    .append("\">").append(seen).append("/").append(runs).append("</td></tr>");
        }
        html.append("</tbody></table>");

        html.append("<p class=\"metrics\">Strict <strong>").append(pct(grid.strict))
                .append("</strong> &nbsp;|&nbsp; Merged <strong>").append(pct(grid.merged))
                .append("</strong>");
        if (!grid.fused.isEmpty()) {
            html.append("<br><span class=\"muted\">fused as one defect: ")
                    .append(escape(String.join(", ", grid.fused))).append("</span>");
        }
        html.append("</p>");

        return html.toString();
    }

    private String findingsHtml(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "<p class=\"empty\">Nothing.</p>";
        }
        return findings.stream()
                .sorted((a, b) -> Integer.compare(a.getLine(), b.getLine()))
                .map(f -> findingHtml(f, false))
                .collect(Collectors.joining());
    }

    private String findingHtml(Finding finding, boolean showSource) {
        String severity = finding.getSeverity().name().toLowerCase();
        StringBuilder html = new StringBuilder("<div class=\"finding\">");
        html.append("<div class=\"finding-head\">");
        if (showSource) {
            String source = finding.getSource() == Finding.Source.CLASSIC ? "classic" : "ai";
            html.append("<span class=\"src ").append(source).append("\">")
                    .append(source).append("</span>");
        } else {
            html.append("<span class=\"ln\">line ").append(finding.getLine()).append("</span>");
        }
        html.append("<span class=\"sev ").append(severity).append("\">")
                .append(finding.getSeverity()).append("</span>");
        html.append("<span class=\"type\">").append(escape(finding.getType()))
                .append("</span></div>");
        html.append("<p class=\"msg\">").append(escape(finding.getMessage())).append("</p>");
        if (finding.getSuggestion() != null && !finding.getSuggestion().isBlank()) {
            html.append("<p class=\"fix\">").append(escape(finding.getSuggestion()))
                    .append("</p>");
        }
        return html.append("</div>").toString();
    }

    private String section(String kind, String title, String subtitle, String content) {
        return "<section class=\"block " + kind + "\">"
                + "<h2>" + escape(title) + "</h2>"
                + "<p class=\"sub\">" + escape(subtitle) + "</p>"
                + content + "</section>";
    }

    private String statsRow(List<String> stats) {
        return "<div class=\"stats\">" + String.join("", stats) + "</div>";
    }

    private String stat(String label, String value, String kind) {
        return "<div class=\"stat " + kind + "\"><div class=\"v\">" + escape(value)
                + "</div><div class=\"l\">" + escape(label) + "</div></div>";
    }

    private String pct(double value) {
        return String.format("%.0f%%", value);
    }

    private Path writePage(String kind, String fileName, String body, Path target)
            throws IOException {

        String stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        String page = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s &mdash; %s</title>
                <style>%s</style>
                </head>
                <body>
                <header>
                  <div class="tag">AI Static Analyzer</div>
                  <h1>%s</h1>
                  <div class="meta">%s &nbsp;&middot;&nbsp; %s</div>
                </header>
                <main>%s</main>
                <footer>Classic AST rules + LLM semantic review &mdash;
                Compilers, Universidad EIA</footer>
                </body>
                </html>
                """.formatted(escape(kind), escape(fileName), css(),
                escape(kind), escape(fileName), stamp, body);

        Files.createDirectories(target.getParent());
        Files.writeString(target, page);
        return target;
    }

    private String css() {
        return """
                *{box-sizing:border-box}
                body{margin:0;background:#0f1115;color:#e6e8ee;
                  font:15px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif}
                header{padding:40px 32px 28px;border-bottom:1px solid #232733;
                  background:linear-gradient(180deg,#151824,#0f1115)}
                .tag{font-size:11px;letter-spacing:.18em;text-transform:uppercase;
                  color:#7c88a1;margin-bottom:10px}
                h1{margin:0;font-size:30px;font-weight:650;letter-spacing:-.02em}
                .meta{margin-top:8px;color:#7c88a1;font-size:13px}
                main{padding:28px 32px;max-width:1080px}
                footer{padding:24px 32px 48px;color:#5d6779;font-size:12px;
                  border-top:1px solid #232733;margin-top:32px}
                .stats{display:flex;gap:14px;flex-wrap:wrap;margin-bottom:26px}
                .stat{flex:1;min-width:130px;padding:16px 18px;border-radius:12px;
                  background:#161a24;border:1px solid #232733}
                .stat .v{font-size:26px;font-weight:650;letter-spacing:-.02em}
                .stat .l{font-size:11px;color:#7c88a1;text-transform:uppercase;
                  letter-spacing:.1em;margin-top:4px}
                .stat.classic .v{color:#6aa9ff}
                .stat.ai .v{color:#c07cff}
                .stat.agree .v{color:#4ad991}
                .block{margin-bottom:34px;padding:22px;border-radius:14px;
                  background:#12151d;border:1px solid #232733}
                .block h2{margin:0;font-size:17px;letter-spacing:-.01em}
                .block .sub{margin:4px 0 18px;color:#7c88a1;font-size:13px}
                .block.classic{border-left:3px solid #6aa9ff}
                .block.ai{border-left:3px solid #c07cff}
                .block.agree{border-left:3px solid #4ad991}
                .finding{padding:14px 16px;border-radius:10px;background:#171b25;
                  border:1px solid #222736;margin-bottom:10px}
                .finding-head{display:flex;align-items:center;gap:10px;
                  flex-wrap:wrap;margin-bottom:7px}
                .ln{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;
                  color:#9aa6bd;background:#0f1219;padding:2px 8px;border-radius:6px}
                .sev{font-size:10px;font-weight:700;letter-spacing:.1em;padding:3px 8px;
                  border-radius:5px;text-transform:uppercase}
                .sev.error{background:#3a1620;color:#ff8095}
                .sev.warning{background:#3a2e12;color:#ffc861}
                .sev.info{background:#12283a;color:#6ec1ff}
                .src{font-size:10px;font-weight:700;letter-spacing:.08em;padding:3px 9px;
                  border-radius:5px;text-transform:uppercase}
                .src.classic{background:#14243d;color:#6aa9ff}
                .src.ai{background:#2a173d;color:#c07cff}
                .type{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;
                  font-weight:600}
                .msg{margin:0;color:#cfd5e2;font-size:14px}
                .fix{margin:6px 0 0;color:#8e9ab1;font-size:13px;padding-left:12px;
                  border-left:2px solid #2b3244}
                .pair{margin-bottom:16px;padding:14px;border-radius:10px;background:#141822;
                  border:1px solid #222736}
                .pair-line{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;
                  color:#4ad991;margin-bottom:10px;letter-spacing:.04em}
                .pair .finding{background:#191e29}
                table.grid{width:100%;border-collapse:collapse;font-size:13px}
                table.grid th{text-align:left;padding:9px 10px;color:#7c88a1;font-weight:600;
                  font-size:11px;text-transform:uppercase;letter-spacing:.1em;
                  border-bottom:1px solid #262c3b}
                table.grid td{padding:9px 10px;border-bottom:1px solid #1c212c}
                table.grid th.run{text-align:center;width:44px}
                td.line{font-family:ui-monospace,Menlo,Consolas,monospace;color:#9aa6bd}
                td.label{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px}
                td.cell{text-align:center;font-weight:700}
                td.cell.hit{color:#4ad991}
                td.cell.miss{color:#4a5266}
                td.seen{font-family:ui-monospace,Menlo,Consolas,monospace;font-weight:700}
                td.seen.full{color:#4ad991}
                td.seen.partial{color:#ffc861}
                .drift{font-size:10px;color:#ffc861;background:#332714;padding:2px 7px;
                  border-radius:5px;margin-left:6px;letter-spacing:.05em}
                .metrics{margin:16px 0 0;font-size:14px;color:#cfd5e2}
                .muted{color:#7c88a1;font-size:12px}
                .note{margin:0 0 24px;padding:16px 18px;border-radius:12px;background:#141822;
                  border:1px solid #222736;color:#9aa6bd;font-size:13px}
                .note strong{color:#e6e8ee}
                .empty{color:#5d6779;font-style:italic;font-size:13px;margin:0}
                """;
    }

    private String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}