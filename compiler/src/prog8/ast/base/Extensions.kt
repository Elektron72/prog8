package prog8.ast.base

import prog8.ast.*
import prog8.ast.expressions.IdentifierReference
import prog8.ast.processing.*
import prog8.ast.statements.Assignment
import prog8.ast.statements.ForLoop
import prog8.compiler.CompilationOptions


// the name of the subroutine that should be called for every block to initialize its variables
internal const val initvarsSubName="prog8_init_vars"


// prefix for literal values that are turned into a variable on the heap
internal const val autoHeapValuePrefix = "auto_heap_value_"


internal fun Program.checkValid(compilerOptions: CompilationOptions) {
    val checker = AstChecker(this, compilerOptions)
    checker.process(this)
    printErrors(checker.result(), name)
}


internal fun Program.reorderStatements() {
    val initvalueCreator = VarInitValueAndAddressOfCreator(namespace)
    initvalueCreator.process(this)

    val checker = StatementReorderer(this)
    checker.process(this)
}

internal fun Module.checkImportedValid() {
    val checker = ImportedAstChecker()
    checker.process(this)
    printErrors(checker.result(), name)
}

internal fun Program.checkRecursion() {
    val checker = AstRecursionChecker(namespace)
    checker.process(this)
    printErrors(checker.result(), name)
}


internal fun Program.checkIdentifiers() {
    val checker = AstIdentifiersChecker(namespace)
    checker.process(this)

    if(modules.map {it.name}.toSet().size != modules.size) {
        throw FatalAstException("modules should all be unique")
    }

    // add any anonymous variables for heap values that are used,
    // and replace an iterable literalvalue by identifierref to new local variable
    for (variable in checker.anonymousVariablesFromHeap.values) {
        val scope = variable.first.definingScope()
        scope.statements.add(variable.second)
        val parent = variable.first.parent
        when {
            parent is Assignment && parent.value === variable.first -> {
                val idref = IdentifierReference(listOf("$autoHeapValuePrefix${variable.first.heapId}"), variable.first.position)
                idref.linkParents(parent)
                parent.value = idref
            }
            parent is IFunctionCall -> {
                val parameterPos = parent.arglist.indexOf(variable.first)
                val idref = IdentifierReference(listOf("$autoHeapValuePrefix${variable.first.heapId}"), variable.first.position)
                idref.linkParents(parent)
                parent.arglist[parameterPos] = idref
            }
            parent is ForLoop -> {
                val idref = IdentifierReference(listOf("$autoHeapValuePrefix${variable.first.heapId}"), variable.first.position)
                idref.linkParents(parent)
                parent.iterable = idref
            }
            else -> TODO("replace literalvalue by identifierref: $variable  (in $parent)")
        }
        variable.second.linkParents(scope as Node)
    }

    printErrors(checker.result(), name)
}