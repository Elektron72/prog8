package prog8.optimizer

import prog8.ast.INameScope
import prog8.ast.Node
import prog8.ast.Program
import prog8.ast.expressions.BinaryExpression
import prog8.ast.expressions.FunctionCall
import prog8.ast.expressions.PrefixExpression
import prog8.ast.expressions.TypecastExpression
import prog8.ast.statements.*
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstModification
import prog8.compiler.IErrorReporter
import prog8.compiler.target.ICompilationTarget
import java.nio.file.Path


internal class UnusedCodeRemover(private val program: Program,
                                 private val errors: IErrorReporter,
                                 private val compTarget: ICompilationTarget,
                                 private val asmFileLoader: (filename: String, source: Path)->String): AstWalker() {

    override fun before(program: Program, parent: Node): Iterable<IAstModification> {
        val callgraph = CallGraph(program, asmFileLoader)
        val removals = mutableListOf<IAstModification>()

        // remove all subroutines that aren't called, or are empty
        // NOTE: part of this is also done already in the StatementOptimizer
        val entrypoint = program.entrypoint()
        program.modules.forEach {
            callgraph.forAllSubroutines(it) { sub ->
                val forceOutput = "force_output" in sub.definingBlock().options()
                if (sub !== entrypoint && !forceOutput && !sub.isAsmSubroutine && (callgraph.calledBy[sub].isNullOrEmpty() || sub.containsNoCodeNorVars())) {
                    removals.add(IAstModification.Remove(sub, sub.definingScope()))
                }
            }
        }

        program.modules.flatMap { it.statements }.filterIsInstance<Block>().forEach { block ->
            if (block.containsNoCodeNorVars() && "force_output" !in block.options())
                removals.add(IAstModification.Remove(block, block.definingScope()))
        }

        // remove modules that are not imported, or are empty (unless it's a library modules)
        program.modules.forEach {
            if (!it.isLibraryModule && (it.importedBy.isEmpty() || it.containsNoCodeNorVars()))
                removals.add(IAstModification.Remove(it, it.definingScope()))
        }

        return removals
    }


    override fun before(breakStmt: Break, parent: Node): Iterable<IAstModification> {
        reportUnreachable(breakStmt, parent as INameScope)
        return emptyList()
    }

    override fun before(jump: Jump, parent: Node): Iterable<IAstModification> {
        reportUnreachable(jump, parent as INameScope)
        return emptyList()
    }

    override fun before(returnStmt: Return, parent: Node): Iterable<IAstModification> {
        reportUnreachable(returnStmt, parent as INameScope)
        return emptyList()
    }

    override fun before(functionCallStatement: FunctionCallStatement, parent: Node): Iterable<IAstModification> {
        if(functionCallStatement.target.nameInSource.last() == "exit")
            reportUnreachable(functionCallStatement, parent as INameScope)
        return emptyList()
    }

    private fun reportUnreachable(stmt: Statement, parent: INameScope) {
        when(val next = parent.nextSibling(stmt)) {
            null, is Label, is Directive, is VarDecl, is InlineAssembly, is Subroutine, is StructDecl -> {}
            else -> errors.warn("unreachable code", next.position)
        }
    }

    override fun after(scope: AnonymousScope, parent: Node): Iterable<IAstModification> {
        val removeDoubleAssignments = deduplicateAssignments(scope.statements)
        return removeDoubleAssignments.map { IAstModification.Remove(it, scope) }
    }

    override fun after(block: Block, parent: Node): Iterable<IAstModification> {
        val removeDoubleAssignments = deduplicateAssignments(block.statements)
        return removeDoubleAssignments.map { IAstModification.Remove(it, block) }
    }

    override fun after(subroutine: Subroutine, parent: Node): Iterable<IAstModification> {
        val removeDoubleAssignments = deduplicateAssignments(subroutine.statements)
        return removeDoubleAssignments.map { IAstModification.Remove(it, subroutine) }
    }

    private fun deduplicateAssignments(statements: List<Statement>): List<Assignment> {
        // removes 'duplicate' assignments that assign the same target directly after another
        val linesToRemove = mutableListOf<Assignment>()

        for (stmtPairs in statements.windowed(2, step = 1)) {
            val assign1 = stmtPairs[0] as? Assignment
            val assign2 = stmtPairs[1] as? Assignment
            if (assign1 != null && assign2 != null && !assign2.isAugmentable) {
                if (assign1.target.isSameAs(assign2.target, program) && compTarget.isInRegularRAM(assign1.target, program))  {
                    if(assign2.target.identifier==null || !assign2.value.referencesIdentifier(*(assign2.target.identifier!!.nameInSource.toTypedArray())))
                        // only remove the second assignment if its value is a simple expression!
                        when(assign2.value) {
                            is PrefixExpression,
                            is BinaryExpression,
                            is TypecastExpression,
                            is FunctionCall -> { /* don't remove */ }
                            else -> linesToRemove.add(assign1)
                        }
                }
            }
        }

        return linesToRemove
    }
}
