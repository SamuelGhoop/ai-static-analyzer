package com.eia.analyzer.classic.rules;

import com.eia.analyzer.model.Finding;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.List;

/**
 * Rule 4: exceptions caught and silently discarded.
 *
 * An empty catch block turns a failure into silence, which is one of the
 * hardest classes of bug to debug in production.
 */
public class EmptyCatchRule extends VoidVisitorAdapter<List<Finding>> {

    @Override
    public void visit(CatchClause catchClause, List<Finding> findings) {
        super.visit(catchClause, findings);

        if (catchClause.getBody().getStatements().isEmpty()) {
            int line = catchClause.getBegin().map(pos -> pos.line).orElse(-1);
            String exceptionType = catchClause.getParameter().getTypeAsString();
            findings.add(new Finding(
                    line,
                    Finding.Severity.WARNING,
                    "EmptyCatchBlock",
                    "'" + exceptionType + "' is caught and silently ignored.",
                    "Log the exception, handle it, or rethrow it as a domain exception.",
                    Finding.Source.CLASSIC
            ));
        }
    }
}
