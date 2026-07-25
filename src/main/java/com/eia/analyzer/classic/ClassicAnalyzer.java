package com.eia.analyzer.classic;

import com.eia.analyzer.classic.rules.EmptyCatchRule;
import com.eia.analyzer.classic.rules.StringEqualityRule;
import com.eia.analyzer.classic.rules.UnreachableCodeRule;
import com.eia.analyzer.classic.rules.UnusedVariableRule;
import com.eia.analyzer.model.Finding;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pass 1 of the analyzer: deterministic static analysis over the AST.
 *
 * Every rule is a Visitor (Gang of Four Visitor Pattern) that walks the tree
 * produced by the parser and appends Findings. Time complexity is O(n) over
 * the number of AST nodes per rule, so O(r * n) for r rules — in practice
 * linear in the size of the source file.
 */
public class ClassicAnalyzer {

    public List<Finding> analyze(CompilationUnit compilationUnit) {
        List<Finding> findings = new ArrayList<>();

        Set<String> stringIdentifiers = collectStringIdentifiers(compilationUnit);

        new UnusedVariableRule().visit(compilationUnit, findings);
        new UnreachableCodeRule().visit(compilationUnit, findings);
        new StringEqualityRule(stringIdentifiers).visit(compilationUnit, findings);
        new EmptyCatchRule().visit(compilationUnit, findings);

        findings.sort(Comparator.comparingInt(Finding::getLine));
        return findings;
    }

    /**
     * Poor man's symbol table: every identifier declared with type String,
     * either as a variable or as a method parameter. The classic pass needs
     * type information to spot reference comparisons, and this is the cheapest
     * approximation of the symbol table a real compiler would build.
     */
    private Set<String> collectStringIdentifiers(CompilationUnit compilationUnit) {
        Set<String> identifiers = new HashSet<>();

        for (VariableDeclarator variable : compilationUnit.findAll(VariableDeclarator.class)) {
            if ("String".equals(variable.getTypeAsString())) {
                identifiers.add(variable.getNameAsString());
            }
        }
        for (Parameter parameter : compilationUnit.findAll(Parameter.class)) {
            if ("String".equals(parameter.getTypeAsString())) {
                identifiers.add(parameter.getNameAsString());
            }
        }
        return identifiers;
    }
}
