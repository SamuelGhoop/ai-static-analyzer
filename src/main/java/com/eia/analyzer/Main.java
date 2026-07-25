package com.eia.analyzer;

import com.eia.analyzer.ai.AiAnalyzer;
import com.eia.analyzer.classic.ClassicAnalyzer;
import com.eia.analyzer.model.Finding;
import com.eia.analyzer.report.ComparativeReport;
import com.eia.analyzer.report.VarianceReport;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI Static Analyzer -- entry point.
 *
 * Usage:
 *   java -jar analyzer.jar &lt;File.java&gt; [options]
 *
 * Options:
 *   --offline           replay the cached AI response instead of calling the API
 *   --classic-only      skip the AI pass entirely
 *   --repeat N          run the reproducibility experiment N times
 *   --temperature X     sampling temperature; omitted entirely if not given
 *                       (recent models reject any non-default value)
 *
 * Pipeline:
 *   1. Parse the file into an AST (JavaParser)
 *   2. Classic pass  : deterministic Visitor rules over the AST
 *   3. AI pass       : LLM-assisted semantic review
 *   4. Comparative report
 */
public class Main {

    public static void main(String[] args) {
        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        Path target = Path.of(args[0]);
        boolean offline = hasFlag(args, "--offline");
        boolean classicOnly = hasFlag(args, "--classic-only");
        int repeat = intFlag(args, "--repeat", 0);
        Double temperature = doubleFlag(args, "--temperature");

        try {
            if (!Files.exists(target)) {
                System.err.println("File not found: " + target.toAbsolutePath());
                System.exit(1);
            }

            String sourceCode = Files.readString(target);
            String fileName = target.getFileName().toString();

            if (repeat > 0) {
                runExperiment(fileName, sourceCode, repeat, temperature);
                return;
            }

            runAnalysis(fileName, sourceCode, offline, classicOnly, temperature);

        } catch (Exception e) {
            System.err.println();
            System.err.println("Analysis failed: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Standard single-run analysis with the side-by-side report.
     */
    private static void runAnalysis(String fileName, String sourceCode,
                                    boolean offline, boolean classicOnly,
                                    Double temperature) throws Exception {
        System.out.println();
        System.out.println("Parsing " + fileName + " ...");
        CompilationUnit compilationUnit = StaticJavaParser.parse(sourceCode);
        System.out.println("  AST built: " + compilationUnit.findAll(Node.class).size() + " nodes");

        System.out.println("Running classic pass (AST rules) ...");
        List<Finding> classicFindings = new ClassicAnalyzer().analyze(compilationUnit);
        System.out.println("  " + classicFindings.size() + " findings");

        List<Finding> aiFindings = Collections.emptyList();
        if (classicOnly) {
            System.out.println("Skipping AI pass (--classic-only)");
        } else {
            System.out.println("Running AI pass (LLM semantic review) ...");
            aiFindings = new AiAnalyzer(temperature).analyze(fileName, sourceCode, offline);
            System.out.println("  " + aiFindings.size() + " findings");
        }

        new ComparativeReport().print(fileName, classicFindings, aiFindings);
    }

    /**
     * Reproducibility experiment: analyze the same file N times with both
     * passes and measure how often each one reproduces itself.
     *
     * The cache is deliberately bypassed here -- replaying a stored answer
     * would guarantee a perfect score and prove nothing.
     */
    private static void runExperiment(String fileName, String sourceCode,
                                      int runs, Double temperature) throws Exception {
        System.out.println();
        System.out.println("Reproducibility experiment on " + fileName);
        System.out.printf("  %d runs, AI temperature %s%n", runs,
                temperature == null ? "model default (not sent)" : String.valueOf(temperature));
        System.out.println();

        List<List<Finding>> classicRuns = new ArrayList<>();
        List<List<Finding>> aiRuns = new ArrayList<>();
        AiAnalyzer aiAnalyzer = new AiAnalyzer(temperature);

        for (int run = 1; run <= runs; run++) {
            System.out.print("  Run " + run + " of " + runs + " ... ");

            CompilationUnit compilationUnit = StaticJavaParser.parse(sourceCode);
            List<Finding> classicFindings = new ClassicAnalyzer().analyze(compilationUnit);
            classicRuns.add(classicFindings);

            List<Finding> aiFindings = aiAnalyzer.analyzeOnce(fileName, sourceCode);
            aiRuns.add(aiFindings);

            System.out.println("classic " + classicFindings.size()
                    + ", ai " + aiFindings.size());
        }

        new VarianceReport().print(fileName, runs, temperature, classicRuns, aiRuns);

        // The variance table shows *which* findings moved between runs, but it
        // strips the explanations. Print run 1 in full underneath, so the
        // reader still gets the readable side-by-side report.
        System.out.println("  Full detail of run 1 follows, for reference.");
        new ComparativeReport().print(fileName, classicRuns.get(0), aiRuns.get(0));
    }

    private static void printUsage() {
        System.out.println("Usage: java -jar analyzer.jar <File.java> [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --offline           replay the cached AI response");
        System.out.println("  --classic-only      skip the AI pass");
        System.out.println("  --repeat N          reproducibility experiment with N runs");
        System.out.println("  --temperature X     sampling temperature (omitted if not given)");
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String arg : args) {
            if (flag.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int intFlag(String[] args, String flag, int fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /**
     * Returns null when the flag is absent, so the caller can omit the
     * parameter from the request entirely rather than guessing a default.
     */
    private static Double doubleFlag(String[] args, String flag) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                try {
                    return Double.parseDouble(args[i + 1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}