package com.eia.analyzer.classic.rules;

import com.eia.analyzer.model.Finding;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.List;
import java.util.Set;

/**
 * Rule 3: comparing String values with == or != instead of .equals().
 *
 * This is a semantic bug the Java compiler happily accepts: the code is
 * perfectly typed, it just compares references instead of contents. Detecting
 * it requires knowing the declared type of each operand, which is why the
 * analyzer collects String-typed identifiers before running this visitor.
 */
public class StringEqualityRule extends VoidVisitorAdapter<List<Finding>> {

    private final Set<String> stringIdentifiers;

    public StringEqualityRule(Set<String> stringIdentifiers) {
        this.stringIdentifiers = stringIdentifiers;
    }

    @Override
    public void visit(BinaryExpr expression, List<Finding> findings) {
        super.visit(expression, findings);

        BinaryExpr.Operator operator = expression.getOperator();
        boolean isEqualityCheck = operator == BinaryExpr.Operator.EQUALS
                || operator == BinaryExpr.Operator.NOT_EQUALS;
        if (!isEqualityCheck) {
            return;
        }

        // Comparing against null with == is correct and idiomatic.
        if (expression.getLeft().isNullLiteralExpr() || expression.getRight().isNullLiteralExpr()) {
            return;
        }

        if (looksLikeString(expression.getLeft()) || looksLikeString(expression.getRight())) {
            int line = expression.getBegin().map(pos -> pos.line).orElse(-1);
            findings.add(new Finding(
                    line,
                    Finding.Severity.ERROR,
                    "StringReferenceComparison",
                    "Strings compared with '" + operator.asString()
                            + "'. This compares object references, not text content.",
                    "Use .equals() instead, for example: a.equals(b).",
                    Finding.Source.CLASSIC
            ));
        }
    }

    private boolean looksLikeString(Expression expression) {
        if (expression.isStringLiteralExpr()) {
            return true;
        }
        if (expression.isNameExpr()) {
            return stringIdentifiers.contains(expression.asNameExpr().getNameAsString());
        }
        return false;
    }
}
