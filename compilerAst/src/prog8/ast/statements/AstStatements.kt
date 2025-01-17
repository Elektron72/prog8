package prog8.ast.statements

import prog8.ast.*
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.walk.AstWalker
import prog8.ast.walk.IAstVisitor


sealed class Statement : Node {
    abstract fun accept(visitor: IAstVisitor)
    abstract fun accept(visitor: AstWalker, parent: Node)

    fun makeScopedName(name: String): String {
        // easy way out is to always return the full scoped name.
        // it would be nicer to find only the minimal prefixed scoped name, but that's too much hassle for now.
        // and like this, we can cache the name even,
        // like in a lazy property on the statement object itself (label, subroutine, vardecl)
        val scope = mutableListOf<String>()
        var statementScope = this.parent
        while(statementScope !is ParentSentinel && statementScope !is Module) {
            if(statementScope is INameScope) {
                scope.add(0, statementScope.name)
            }
            statementScope = statementScope.parent
        }
        if(name.isNotEmpty())
            scope.add(name)
        return scope.joinToString(".")
    }
}


class BuiltinFunctionStatementPlaceholder(val name: String, override val position: Position) : Statement() {
    override var parent: Node = ParentSentinel
    override fun linkParents(parent: Node) {}
    override fun accept(visitor: IAstVisitor) = throw FatalAstException("should not iterate over this node")
    override fun accept(visitor: AstWalker, parent: Node) = throw FatalAstException("should not iterate over this node")
    override fun definingScope(): INameScope = BuiltinFunctionScopePlaceholder
    override fun replaceChildNode(node: Node, replacement: Node) {
        replacement.parent = this
    }
}

data class RegisterOrStatusflag(val registerOrPair: RegisterOrPair?, val statusflag: Statusflag?)

class Block(override val name: String,
            val address: Int?,
            override var statements: MutableList<Statement>,
            val isInLibrary: Boolean,
            override val position: Position) : Statement(), INameScope {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        statements.forEach {it.linkParents(this)}
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Statement)
        val idx = statements.indexOfFirst { it ===node }
        statements[idx] = replacement
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "Block(name=$name, address=$address, ${statements.size} statements)"
    }

    fun options() = statements.filter { it is Directive && it.directive == "%option" }.flatMap { (it as Directive).args }.map {it.name!!}.toSet()
}

data class Directive(val directive: String, val args: List<DirectiveArg>, override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        args.forEach{it.linkParents(this)}
    }

    override fun replaceChildNode(node: Node, replacement: Node) = throw FatalAstException("can't replace here")
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}

data class DirectiveArg(val str: String?, val name: String?, val int: Int?, override val position: Position) : Node {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
    }
    override fun replaceChildNode(node: Node, replacement: Node) = throw FatalAstException("can't replace here")
}

data class Label(val name: String, override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
    }

    override fun replaceChildNode(node: Node, replacement: Node) = throw FatalAstException("can't replace here")
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "Label(name=$name, pos=$position)"
    }
}

open class Return(var value: Expression?, override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        value?.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Expression)
        value = replacement
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "Return($value, pos=$position)"
    }
}

class Break(override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent=parent
    }

    override fun replaceChildNode(node: Node, replacement: Node) = throw FatalAstException("can't replace here")
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}


enum class ZeropageWish {
    REQUIRE_ZEROPAGE,
    PREFER_ZEROPAGE,
    DONTCARE,
    NOT_IN_ZEROPAGE
}


open class VarDecl(val type: VarDeclType,
                   private val declaredDatatype: DataType,
                   val zeropage: ZeropageWish,
                   var arraysize: ArrayIndex?,
                   val name: String,
                   private val structName: String?,
                   var value: Expression?,
                   val isArray: Boolean,
                   val autogeneratedDontRemove: Boolean,
                   override val position: Position) : Statement() {
    override lateinit var parent: Node
    var struct: StructDecl? = null        // set later (because at parse time, we only know the name)
        private set
    var structHasBeenFlattened = false      // set later
        private set
    var allowInitializeWithZero = true

    // prefix for literal values that are turned into a variable on the heap

    companion object {
        private var autoHeapValueSequenceNumber = 0

        fun createAuto(array: ArrayLiteralValue): VarDecl {
            val autoVarName = "auto_heap_value_${++autoHeapValueSequenceNumber}"
            val arrayDt =
                if(!array.type.isKnown)
                    throw FatalAstException("unknown dt")
                else
                    array.type.typeOrElse(DataType.STRUCT)
            val declaredType = ArrayElementTypes.getValue(arrayDt)
            val arraysize = ArrayIndex.forArray(array)
            return VarDecl(VarDeclType.VAR, declaredType, ZeropageWish.NOT_IN_ZEROPAGE, arraysize, autoVarName, null, array,
                    isArray = true, autogeneratedDontRemove = true, position = array.position)
        }

        fun defaultZero(dt: DataType, position: Position) = when(dt) {
            DataType.UBYTE -> NumericLiteralValue(DataType.UBYTE, 0,  position)
            DataType.BYTE -> NumericLiteralValue(DataType.BYTE, 0,  position)
            DataType.UWORD, DataType.STR -> NumericLiteralValue(DataType.UWORD, 0, position)
            DataType.WORD -> NumericLiteralValue(DataType.WORD, 0, position)
            DataType.FLOAT -> NumericLiteralValue(DataType.FLOAT, 0.0, position)
            else -> throw FatalAstException("can only determine default zero value for a numeric type")
        }
    }

    val datatypeErrors = mutableListOf<SyntaxError>()       // don't crash at init time, report them in the AstChecker
    val datatype =
            if (!isArray) declaredDatatype
            else when (declaredDatatype) {
                DataType.UBYTE -> DataType.ARRAY_UB
                DataType.BYTE -> DataType.ARRAY_B
                DataType.UWORD -> DataType.ARRAY_UW
                DataType.WORD -> DataType.ARRAY_W
                DataType.FLOAT -> DataType.ARRAY_F
                DataType.STR -> DataType.ARRAY_UW       // use memory address of the string instead
                else -> {
                    datatypeErrors.add(SyntaxError("array can only contain bytes/words/floats/strings(ptrs)", position))
                    DataType.ARRAY_UB
                }
            }

    override fun linkParents(parent: Node) {
        this.parent = parent
        arraysize?.linkParents(this)
        value?.linkParents(this)
        if(structName!=null) {
            val structStmt = definingScope().lookup(listOf(structName), this)
            if(structStmt!=null) {
                val node = definingScope().lookup(listOf(structName), this)
                if(node is StructDecl)
                    struct = node
                else
                    datatypeErrors.add(SyntaxError("invalid datatype declaration", position))
            }
        }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Expression && node===value)
        // NOTE: ideally you also want to check that node===value but this sometimes crashes the optimizer when queueing multiple node replacements
        //       just accept the risk of having the wrong node specified in the IAstModification object...
        value = replacement
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "VarDecl(name=$name, vartype=$type, datatype=$datatype, struct=$structName, value=$value, pos=$position)"
    }

    fun zeroElementValue(): NumericLiteralValue {
        if(allowInitializeWithZero)
            return defaultZero(declaredDatatype, position)
        else
            throw IllegalArgumentException("attempt to get zero value for vardecl that shouldn't get it")
    }

    fun flattenStructMembers(): MutableList<Statement> {
        val result = struct!!.statements.mapIndexed { index, statement ->
            val member = statement as VarDecl
            val initvalue = if(value!=null) (value as ArrayLiteralValue).value[index] else null
            VarDecl(
                    VarDeclType.VAR,
                    member.datatype,
                    ZeropageWish.NOT_IN_ZEROPAGE,
                    member.arraysize,
                    mangledStructMemberName(name, member.name),
                    struct!!.name,
                    initvalue,
                    member.isArray,
                    true,
                    member.position
            )
        }.toMutableList<Statement>()
        structHasBeenFlattened = true
        return result
    }
}

// a vardecl used only for subroutine parameters
class ParameterVarDecl(name: String, declaredDatatype: DataType, position: Position)
    : VarDecl(VarDeclType.VAR, declaredDatatype, ZeropageWish.DONTCARE, null, name, null, null, false, true, position)


class ArrayIndex(var indexExpr: Expression,
                 override val position: Position) : Node {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        indexExpr.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Expression)
        if (node===indexExpr) indexExpr = replacement
        else throw FatalAstException("invalid replace")
    }

    companion object {
        fun forArray(v: ArrayLiteralValue): ArrayIndex {
            val indexnum = NumericLiteralValue.optimalNumeric(v.value.size, v.position)
            return ArrayIndex(indexnum, v.position)
        }
    }

    fun accept(visitor: IAstVisitor) = indexExpr.accept(visitor)
    fun accept(visitor: AstWalker, parent: Node)  = indexExpr.accept(visitor, this)

    override fun toString(): String {
        return("ArrayIndex($indexExpr, pos=$position)")
    }

    fun constIndex() = (indexExpr as? NumericLiteralValue)?.number?.toInt()

    infix fun isSameAs(other: ArrayIndex): Boolean = indexExpr isSameAs other.indexExpr
}

open class Assignment(var target: AssignTarget, var value: Expression, override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        this.target.linkParents(this)
        value.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node===target -> target = replacement as AssignTarget
            node===value -> value = replacement as Expression
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return("Assignment(target: $target, value: $value, pos=$position)")
    }

    /**
     * Is the assigment value an expression that references the assignment target itself?
     * The expression can be a BinaryExpression, PrefixExpression or TypecastExpression (possibly with one sub-cast).
     */
    val isAugmentable: Boolean
        get() {
            val binExpr = value as? BinaryExpression
            if(binExpr!=null) {
                if(binExpr.left isSameAs target)
                    return true  // A = A <operator> Something

                if(binExpr.operator in associativeOperators) {
                    if (binExpr.left !is BinaryExpression && binExpr.right isSameAs target)
                        return true  // A = v <associative-operator> A

                    val leftBinExpr = binExpr.left as? BinaryExpression
                    if(leftBinExpr?.operator == binExpr.operator) {
                        // one of these?
                        // A = (A <associative-operator> x) <same-operator> y
                        // A = (x <associative-operator> A) <same-operator> y
                        // A = (x <associative-operator> y) <same-operator> A
                        return leftBinExpr.left isSameAs target || leftBinExpr.right isSameAs target || binExpr.right isSameAs target
                    }
                    val rightBinExpr = binExpr.right as? BinaryExpression
                    if(rightBinExpr?.operator == binExpr.operator) {
                        // one of these?
                        // A = y <associative-operator> (A <same-operator> x)
                        // A = y <associative-operator> (x <same-operator> y)
                        // A = A <associative-operator> (x <same-operator> y)
                        return rightBinExpr.left isSameAs target || rightBinExpr.right isSameAs target || binExpr.left isSameAs target
                    }
                }
            }

            val prefixExpr = value as? PrefixExpression
            if(prefixExpr!=null)
                return prefixExpr.expression isSameAs target

            val castExpr = value as? TypecastExpression
            if(castExpr!=null) {
                val subCast = castExpr.expression as? TypecastExpression
                return if(subCast!=null) subCast.expression isSameAs target else castExpr.expression isSameAs target
            }

            return false
        }
}

data class AssignTarget(var identifier: IdentifierReference?,
                        var arrayindexed: ArrayIndexedExpression?,
                        val memoryAddress: DirectMemoryWrite?,
                        override val position: Position) : Node {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        identifier?.linkParents(this)
        arrayindexed?.linkParents(this)
        memoryAddress?.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node === identifier -> identifier = replacement as IdentifierReference
            node === arrayindexed -> arrayindexed = replacement as ArrayIndexedExpression
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    fun accept(visitor: IAstVisitor) = visitor.visit(this)
    fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    fun inferType(program: Program): InferredTypes.InferredType {
        if (identifier != null) {
            val symbol = program.namespace.lookup(identifier!!.nameInSource, this) ?: return InferredTypes.unknown()
            if (symbol is VarDecl) return InferredTypes.knownFor(symbol.datatype)
        }

        if (arrayindexed != null) {
            return arrayindexed!!.inferType(program)
        }

        if (memoryAddress != null)
            return InferredTypes.knownFor(DataType.UBYTE)

        return InferredTypes.unknown()
    }

    fun toExpression(): Expression {
        return when {
            identifier != null -> identifier!!
            arrayindexed != null -> arrayindexed!!
            memoryAddress != null -> DirectMemoryRead(memoryAddress.addressExpression, memoryAddress.position)
            else -> throw FatalAstException("invalid assignmenttarget $this")
        }
    }

    infix fun isSameAs(value: Expression): Boolean {
        return when {
            memoryAddress != null -> {
                // if the target is a memory write, and the value is a memory read, they're the same if the address matches
                if (value is DirectMemoryRead)
                    this.memoryAddress.addressExpression isSameAs value.addressExpression
                else
                    false
            }
            identifier != null -> value is IdentifierReference && value.nameInSource == identifier!!.nameInSource
            arrayindexed != null -> {
                if(value is ArrayIndexedExpression && value.arrayvar.nameInSource == arrayindexed!!.arrayvar.nameInSource)
                    arrayindexed!!.indexer isSameAs value.indexer
                else
                    false
            }
            else -> false
        }
    }

    fun isSameAs(other: AssignTarget, program: Program): Boolean {
        if (this === other)
            return true
        if (this.identifier != null && other.identifier != null)
            return this.identifier!!.nameInSource == other.identifier!!.nameInSource
        if (this.memoryAddress != null && other.memoryAddress != null) {
            val addr1 = this.memoryAddress.addressExpression.constValue(program)
            val addr2 = other.memoryAddress.addressExpression.constValue(program)
            return addr1 != null && addr2 != null && addr1 == addr2
        }
        if (this.arrayindexed != null && other.arrayindexed != null) {
            if (this.arrayindexed!!.arrayvar.nameInSource == other.arrayindexed!!.arrayvar.nameInSource) {
                val x1 = this.arrayindexed!!.indexer.constIndex()
                val x2 = other.arrayindexed!!.indexer.constIndex()
                return x1 != null && x2 != null && x1 == x2
            }
        }
        return false
    }
}


class PostIncrDecr(var target: AssignTarget, val operator: String, override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        target.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is AssignTarget && node===target)
        target = replacement
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "PostIncrDecr(op: $operator, target: $target, pos=$position)"
    }
}

class Jump(val address: Int?,
           val identifier: IdentifierReference?,
           val generatedLabel: String?,             // used in code generation scenarios
           override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        identifier?.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) = throw FatalAstException("can't replace here")
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "Jump(addr: $address, identifier: $identifier, label: $generatedLabel;  pos=$position)"
    }
}

class FunctionCallStatement(override var target: IdentifierReference,
                            override var args: MutableList<Expression>,
                            val void: Boolean,
                            override val position: Position) : Statement(), IFunctionCall {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        target.linkParents(this)
        args.forEach { it.linkParents(this) }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        if(node===target)
            target = replacement as IdentifierReference
        else {
            val idx = args.indexOfFirst { it===node }
            args[idx] = replacement as Expression
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "FunctionCallStatement(target=$target, pos=$position)"
    }
}

class InlineAssembly(val assembly: String, override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
    }

    override fun replaceChildNode(node: Node, replacement: Node) = throw FatalAstException("can't replace here")
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}

class AnonymousScope(override var statements: MutableList<Statement>,
                     override val position: Position) : INameScope, Statement() {
    override val name: String
    override lateinit var parent: Node

    companion object {
        private var sequenceNumber = 1
    }

    init {
        name = "<anon-$sequenceNumber>"     // make sure it's an invalid soruce code identifier so user source code can never produce it
        sequenceNumber++
    }

    override fun linkParents(parent: Node) {
        this.parent = parent
        statements.forEach { it.linkParents(this) }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Statement)
        val idx = statements.indexOfFirst { it===node }
        statements[idx] = replacement
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}

class NopStatement(override val position: Position): Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
    }

    override fun replaceChildNode(node: Node, replacement: Node) = throw FatalAstException("can't replace here")
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}


class AsmGenInfo {
    // This class contains various attributes that influence the assembly code generator.
    // Conceptually it should be part of any INameScope.
    // But because the resulting code only creates "real" scopes on a subroutine level,
    // it's more consistent to only define these attributes on a Subroutine node.
    var usedRegsaveA = false
    var usedRegsaveX = false
    var usedRegsaveY = false
    var usedFloatEvalResultVar1 = false
    var usedFloatEvalResultVar2 = false
}

// the subroutine class covers both the normal user-defined subroutines,
// and also the predefined/ROM/register-based subroutines.
// (multiple return types can only occur for the latter type)
class Subroutine(override val name: String,
                 val parameters: List<SubroutineParameter>,
                 val returntypes: List<DataType>,
                 val asmParameterRegisters: List<RegisterOrStatusflag>,
                 val asmReturnvaluesRegisters: List<RegisterOrStatusflag>,
                 val asmClobbers: Set<CpuRegister>,
                 val asmAddress: Int?,
                 val isAsmSubroutine: Boolean,
                 val inline: Boolean,
                 override var statements: MutableList<Statement>,
                 override val position: Position) : Statement(), INameScope {

    constructor(name: String, parameters: List<SubroutineParameter>, returntypes: List<DataType>, statements: MutableList<Statement>, inline: Boolean, position: Position)
            : this(name, parameters, returntypes, emptyList(), determineReturnRegisters(returntypes), emptySet(), null, false, inline, statements, position)

    companion object {
        private fun determineReturnRegisters(returntypes: List<DataType>): List<RegisterOrStatusflag> {
            // for non-asm subroutines, determine the return registers based on the type of the return value
            return when(returntypes.singleOrNull()) {
                in ByteDatatypes -> listOf(RegisterOrStatusflag(RegisterOrPair.A, null))
                in WordDatatypes -> listOf(RegisterOrStatusflag(RegisterOrPair.AY, null))
                DataType.FLOAT -> listOf(RegisterOrStatusflag(RegisterOrPair.FAC1, null))
                null -> emptyList()
                else -> listOf(RegisterOrStatusflag(RegisterOrPair.AY, null))
            }
        }
    }

    override lateinit var parent: Node
    val asmGenInfo = AsmGenInfo()
    val scopedname: String by lazy { makeScopedName(name) }

    override fun linkParents(parent: Node) {
        this.parent = parent
        parameters.forEach { it.linkParents(this) }
        statements.forEach { it.linkParents(this) }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Statement)
        val idx = statements.indexOfFirst { it===node }
        statements[idx] = replacement
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "Subroutine(name=$name, parameters=$parameters, returntypes=$returntypes, ${statements.size} statements, address=$asmAddress)"
    }

    fun regXasResult() = asmReturnvaluesRegisters.any { it.registerOrPair in setOf(RegisterOrPair.X, RegisterOrPair.AX, RegisterOrPair.XY) }
    fun regXasParam() = asmParameterRegisters.any { it.registerOrPair in setOf(RegisterOrPair.X, RegisterOrPair.AX, RegisterOrPair.XY) }
    fun shouldSaveX() = CpuRegister.X in asmClobbers || regXasResult() || regXasParam()
    fun shouldKeepA(): Pair<Boolean, Boolean> {
        // determine if A's value should be kept when preparing for calling the subroutine, and when returning from it
        if(!isAsmSubroutine)
            return Pair(false, false)

        // it seems that we never have to save A when calling? will be loaded correctly after setup.
        // but on return it depends on wether the routine returns something in A.
        val saveAonReturn = asmReturnvaluesRegisters.any { it.registerOrPair==RegisterOrPair.A || it.registerOrPair==RegisterOrPair.AY || it.registerOrPair==RegisterOrPair.AX }
        return Pair(false, saveAonReturn)
    }

    fun amountOfRtsInAsm(): Int = statements
            .asSequence()
            .filter { it is InlineAssembly }
            .map { (it as InlineAssembly).assembly }
            .count { " rti" in it || "\trti" in it || " rts" in it || "\trts" in it || " jmp" in it || "\tjmp" in it || " bra" in it || "\tbra" in it}
}


open class SubroutineParameter(val name: String,
                               val type: DataType,
                               override val position: Position) : Node {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        throw FatalAstException("can't replace anything in a subroutineparameter node")
    }
}

class IfStatement(var condition: Expression,
                  var truepart: AnonymousScope,
                  var elsepart: AnonymousScope,
                  override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        condition.linkParents(this)
        truepart.linkParents(this)
        elsepart.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node===condition -> condition = replacement as Expression
            node===truepart -> truepart = replacement as AnonymousScope
            node===elsepart -> elsepart = replacement as AnonymousScope
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

}

class BranchStatement(var condition: BranchCondition,
                      var truepart: AnonymousScope,
                      var elsepart: AnonymousScope,
                      override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        truepart.linkParents(this)
        elsepart.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node===truepart -> truepart = replacement as AnonymousScope
            node===elsepart -> elsepart = replacement as AnonymousScope
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

}

class ForLoop(var loopVar: IdentifierReference,
              var iterable: Expression,
              var body: AnonymousScope,
              override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent=parent
        loopVar.linkParents(this)
        iterable.linkParents(this)
        body.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node===loopVar -> loopVar = replacement as IdentifierReference
            node===iterable -> iterable = replacement as Expression
            node===body -> body = replacement as AnonymousScope
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    override fun toString(): String {
        return "ForLoop(loopVar: $loopVar, iterable: $iterable, pos=$position)"
    }

    fun loopVarDt(program: Program) = loopVar.inferType(program)
}

class WhileLoop(var condition: Expression,
                var body: AnonymousScope,
                override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        condition.linkParents(this)
        body.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node===condition -> condition = replacement as Expression
            node===body -> body = replacement as AnonymousScope
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}

class RepeatLoop(var iterations: Expression?, var body: AnonymousScope, override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        iterations?.linkParents(this)
        body.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node===iterations -> iterations = replacement as Expression
            node===body -> body = replacement as AnonymousScope
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}

class UntilLoop(var body: AnonymousScope,
                var condition: Expression,
                override val position: Position) : Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        condition.linkParents(this)
        body.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        when {
            node===condition -> condition = replacement as Expression
            node===body -> body = replacement as AnonymousScope
            else -> throw FatalAstException("invalid replace")
        }
        replacement.parent = this
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}

class WhenStatement(var condition: Expression,
                    var choices: MutableList<WhenChoice>,
                    override val position: Position): Statement() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        condition.linkParents(this)
        choices.forEach { it.linkParents(this) }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        if(node===condition)
            condition = replacement as Expression
        else {
            val idx = choices.withIndex().find { it.value===node }!!.index
            choices[idx] = replacement as WhenChoice
        }
        replacement.parent = this
    }

    fun choiceValues(program: Program): List<Pair<List<Int>?, WhenChoice>> {
        // only gives sensible results when the choices are all valid (constant integers)
        val result = mutableListOf<Pair<List<Int>?, WhenChoice>>()
        for(choice in choices) {
            if(choice.values==null)
                result.add(null to choice)
            else {
                val values = choice.values!!.map {
                    val cv = it.constValue(program)
                    cv?.number?.toInt() ?: it.hashCode()       // the hashcode is a nonsensical number but it avoids weird AST validation errors later
                }
                result.add(values to choice)
            }
        }
        return result
    }

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}

class WhenChoice(var values: MutableList<Expression>?,           // if null,  this is the 'else' part
                 var statements: AnonymousScope,
                 override val position: Position) : Node {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        values?.forEach { it.linkParents(this) }
        statements.linkParents(this)
        this.parent = parent
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        val choiceValues = values
        if(replacement is AnonymousScope && node===statements) {
            statements = replacement
            replacement.parent = this
        } else if(choiceValues!=null && node in choiceValues) {
            val idx = choiceValues.indexOf(node)
            choiceValues[idx] = replacement as Expression
            replacement.parent = this
        } else {
            throw FatalAstException("invalid replacement")
        }
    }

    override fun toString(): String {
        return "Choice($values at $position)"
    }

    fun accept(visitor: IAstVisitor) = visitor.visit(this)
    fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}


class StructDecl(override val name: String,
                 override var statements: MutableList<Statement>,      // actually, only vardecls here
                 override val position: Position): Statement(), INameScope {

    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        this.statements.forEach { it.linkParents(this) }
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Statement)
        val idx = statements.indexOfFirst { it===node }
        statements[idx] = replacement
        replacement.parent = this
    }

    val numberOfElements: Int
        get() = this.statements.size

    fun memsize(memsizer: IMemSizer) =
        statements.map { memsizer.memorySize((it as VarDecl).datatype) }.sum()

    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)

    fun nameOfFirstMember() = (statements.first() as VarDecl).name
}

class DirectMemoryWrite(var addressExpression: Expression, override val position: Position) : Node {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        this.addressExpression.linkParents(this)
    }

    override fun replaceChildNode(node: Node, replacement: Node) {
        require(replacement is Expression && node===addressExpression)
        addressExpression = replacement
        replacement.parent = this
    }

    override fun toString(): String {
        return "DirectMemoryWrite($addressExpression)"
    }

    fun accept(visitor: IAstVisitor) = visitor.visit(this)
    fun accept(visitor: AstWalker, parent: Node) = visitor.visit(this, parent)
}
