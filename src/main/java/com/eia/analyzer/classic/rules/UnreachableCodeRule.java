package com.eia.analyzer.classic.rules;

import com.eia.analyzer.model.Finding;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.util.List;

/**
 * Rule 2: statements that can never execute because control flow already left
 * the block (return / throw / break / continue).
 *
 * This is the classic "dead code" check a real compiler performs on its
 * control flow graph. Here we approximate it at block level: inside a single
 * block, anything after a terminating statement is unreachable.
 */
public class UnreachableCodeRule extends VoidVisitorAdapter<List<Finding>> {

    @Override
    public void visit(BlockStmt block, List<Finding> findings) {
        super.visit(block, findings);

        NodeList<Statement> statements = block.getStatements();
        for (int i = 0; i < statements.size() - 1; i++) {
            Statement current = statements.get(i);
            boolean terminates = current.isReturnStmt()
                    || current.isThrowStmt()
                    || current.isBreakStmt()
                    || current.isContinueStmt();

            if (terminates) {
                Statement dead = statements.get(i + 1);
                int line = dead.getBegin().map(pos -> pos.line).orElse(-1);
                findings.add(new Finding(
                        line,
                        Finding.Severity.ERROR,
                        "UnreachableCode",
                        "This statement can never be executed: control flow already "
                                + "left the block at line "
                                + current.getBegin().map(pos -> pos.line).orElse(-1) + ".",
                        "Delete the dead code, or move it before the terminating statement.",
                        Finding.Source.CLASSIC
                ));
                // One report per block is enough; everything after is dead too.
                return;
            }
        }
    }
}
