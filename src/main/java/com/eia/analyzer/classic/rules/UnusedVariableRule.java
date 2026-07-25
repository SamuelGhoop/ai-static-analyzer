package com.eia.analyzer.classic.rules;

import com.eia.analyzer.model.Finding;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rule 1: local variables that are declared but never referenced.
 *
 * Strategy: for every method body, collect every declared variable name and
 * every identifier actually referenced (NameExpr). Anything declared but not
 * referenced is dead storage.
 *
 * Known limitation (worth mentioning in the presentation): a variable that is
 * only ever written to, never read, still counts as "used" because the
 * assignment target is itself a NameExpr. A deterministic rule sees syntax,
 * not intent.
 */
public class UnusedVariableRule extends VoidVisitorAdapter<List<Finding>> {

    @Override
    public void visit(MethodDeclaration method, List<Finding> findings) {
        super.visit(method, findings);

        if (method.getBody().isEmpty()) {
            return;
        }
        BlockStmt body = method.getBody().get();

        Set<String> referenced = new HashSet<>();
        for (NameExpr name : body.findAll(NameExpr.class)) {
            referenced.add(name.getNameAsString());
        }

        for (VariableDeclarator variable : body.findAll(VariableDeclarator.class)) {
            String name = variable.getNameAsString();
            if (!referenced.contains(name)) {
                int line = variable.getBegin().map(pos -> pos.line).orElse(-1);
                findings.add(new Finding(
                        line,
                        Finding.Severity.WARNING,
                        "UnusedVariable",
                        "Variable '" + name + "' is declared but never used.",
                        "Remove the declaration, or use the value it holds.",
                        Finding.Source.CLASSIC
                ));
            }
        }
    }
}
