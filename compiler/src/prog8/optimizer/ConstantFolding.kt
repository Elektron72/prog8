package prog8.optimizer

import prog8.ast.*
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.processing.IAstProcessor
import prog8.ast.statements.*
import prog8.compiler.HeapValues
import prog8.compiler.IntegerOrAddressOf
import prog8.compiler.target.c64.FLOAT_MAX_NEGATIVE
import prog8.compiler.target.c64.FLOAT_MAX_POSITIVE
import kotlin.math.floor


class ConstantFolding(private val program: Program) : IAstProcessor {
    var optimizationsDone: Int = 0
    var errors : MutableList<AstException> = mutableListOf()

    private val reportedErrorMessages = mutableSetOf<String>()

    fun addError(x: AstException) {
        // check that we don't add the isSameAs error more than once
        if(x.toString() !in reportedErrorMessages) {
            reportedErrorMessages.add(x.toString())
            errors.add(x)
        }
    }

    override fun process(decl: VarDecl): IStatement {
        // the initializer value can't refer to the variable itself (recursive definition)
        if(decl.value?.referencesIdentifier(decl.name) == true || decl.arraysize?.index?.referencesIdentifier(decl.name) == true) {
            errors.add(ExpressionError("recursive var declaration", decl.position))
            return decl
        }

        if(decl.type==VarDeclType.CONST || decl.type==VarDeclType.VAR) {
            val litval = decl.value as? LiteralValue
            if(litval!=null && litval.isArray && litval.heapId!=null)
                fixupArrayTypeOnHeap(decl, litval)

            if(decl.isArray){
                // for arrays that have no size specifier (or a non-constant one) attempt to deduce the size
                if(decl.arraysize==null) {
                    val arrayval = (decl.value as? LiteralValue)?.arrayvalue
                    if(arrayval!=null) {
                        decl.arraysize = ArrayIndex(LiteralValue.optimalInteger(arrayval.size, decl.position), decl.position)
                        optimizationsDone++
                    }
                }
                else if(decl.arraysize?.size()==null) {
                    val size = decl.arraysize!!.index.process(this)
                    if(size is LiteralValue) {
                        decl.arraysize = ArrayIndex(size, decl.position)
                        optimizationsDone++
                    }
                }
            }

            when(decl.datatype) {
                DataType.FLOAT -> {
                    // vardecl: for scalar float vars, promote constant integer initialization values to floats
                    if (litval != null && litval.type in IntegerDatatypes) {
                        val newValue = LiteralValue(DataType.FLOAT, floatvalue = litval.asNumericValue!!.toDouble(), position = litval.position)
                        decl.value = newValue
                        optimizationsDone++
                        return decl
                    }
                }
                DataType.ARRAY_UB, DataType.ARRAY_B, DataType.ARRAY_UW, DataType.ARRAY_W -> {
                    val rangeExpr = decl.value as? RangeExpr
                    if(rangeExpr!=null) {
                        // convert the initializer range expression to an actual array (will be put on heap later)
                        val declArraySize = decl.arraysize?.size()
                        if(declArraySize!=null && declArraySize!=rangeExpr.size())
                            errors.add(ExpressionError("range expression size doesn't match declared array size", decl.value?.position!!))
                        val constRange = rangeExpr.toConstantIntegerRange()
                        if(constRange!=null) {
                            val eltType = rangeExpr.inferType(program)!!
                            if(eltType in ByteDatatypes) {
                                decl.value = LiteralValue(decl.datatype,
                                        arrayvalue = constRange.map { LiteralValue(eltType, bytevalue = it.toShort(), position = decl.value!!.position) }
                                                .toTypedArray(), position = decl.value!!.position)
                            } else {
                                decl.value = LiteralValue(decl.datatype,
                                        arrayvalue = constRange.map { LiteralValue(eltType, wordvalue = it, position = decl.value!!.position) }
                                                .toTypedArray(), position = decl.value!!.position)
                            }
                            decl.value!!.linkParents(decl)
                            optimizationsDone++
                            return decl
                        }
                    }
                    if(litval?.type== DataType.FLOAT)
                        errors.add(ExpressionError("arraysize requires only integers here", litval.position))
                    val size = decl.arraysize?.size() ?: return decl
                    if ((litval==null || !litval.isArray) && rangeExpr==null) {
                        // arraysize initializer is empty or a single int, and we know the size; create the arraysize.
                        val fillvalue = if (litval == null) 0 else litval.asIntegerValue ?: 0
                        when(decl.datatype){
                            DataType.ARRAY_UB -> {
                                if(fillvalue !in 0..255)
                                    errors.add(ExpressionError("ubyte value overflow", litval?.position
                                            ?: decl.position))
                            }
                            DataType.ARRAY_B -> {
                                if(fillvalue !in -128..127)
                                    errors.add(ExpressionError("byte value overflow", litval?.position
                                            ?: decl.position))
                            }
                            DataType.ARRAY_UW -> {
                                if(fillvalue !in 0..65535)
                                    errors.add(ExpressionError("uword value overflow", litval?.position
                                            ?: decl.position))
                            }
                            DataType.ARRAY_W -> {
                                if(fillvalue !in -32768..32767)
                                    errors.add(ExpressionError("word value overflow", litval?.position
                                            ?: decl.position))
                            }
                            else -> {}
                        }
                        val heapId = program.heap.addIntegerArray(decl.datatype, Array(size) { IntegerOrAddressOf(fillvalue, null) })
                        decl.value = LiteralValue(decl.datatype, initHeapId = heapId, position = litval?.position
                                ?: decl.position)
                        optimizationsDone++
                        return decl
                    }
                }
                DataType.ARRAY_F  -> {
                    val size = decl.arraysize?.size() ?: return decl
                    if (litval==null || !litval.isArray) {
                        // arraysize initializer is empty or a single int, and we know the size; create the arraysize.
                        val fillvalue = if (litval == null) 0.0 else litval.asNumericValue?.toDouble() ?: 0.0
                        if(fillvalue< FLOAT_MAX_NEGATIVE || fillvalue> FLOAT_MAX_POSITIVE)
                            errors.add(ExpressionError("float value overflow", litval?.position
                                    ?: decl.position))
                        else {
                            val heapId = program.heap.addDoublesArray(DoubleArray(size) { fillvalue })
                            decl.value = LiteralValue(DataType.ARRAY_F, initHeapId = heapId, position = litval?.position
                                    ?: decl.position)
                            optimizationsDone++
                            return decl
                        }
                    }
                }
                else -> {
                    // nothing to do for this type
                }
            }
        }

        return super.process(decl)
    }

    private fun fixupArrayTypeOnHeap(decl: VarDecl, litval: LiteralValue) {
        // fix the type of the array value that's on the heap, to match the vardecl.
        // notice that checking the bounds of the actual values is not done here, but in the AstChecker later.

        if(decl.datatype==litval.type)
            return   // already correct datatype
        val heapId = litval.heapId ?: throw FatalAstException("expected array to be on heap $litval")
        val array = program.heap.get(heapId)
        when(decl.datatype) {
            DataType.ARRAY_UB, DataType.ARRAY_B, DataType.ARRAY_UW, DataType.ARRAY_W -> {
                if(array.array!=null) {
                    program.heap.update(heapId, HeapValues.HeapValue(decl.datatype, null, array.array, null))
                    decl.value = LiteralValue(decl.datatype, initHeapId = heapId, position = litval.position)
                }
            }
            DataType.ARRAY_F -> {
                if(array.array!=null) {
                    // convert a non-float array to floats
                    val doubleArray = array.array.map { it.integer!!.toDouble() }.toDoubleArray()
                    program.heap.update(heapId, HeapValues.HeapValue(DataType.ARRAY_F, null, null, doubleArray))
                    decl.value = LiteralValue(decl.datatype, initHeapId = heapId, position = litval.position)
                }
            }
            else -> throw FatalAstException("invalid array vardecl type ${decl.datatype}")
        }
    }

    /**
     * replace identifiers that refer to const value, with the value itself (if it's a simple type)
     */
    override fun process(identifier: IdentifierReference): IExpression {
        return try {
            val cval = identifier.constValue(program) ?: return identifier
            return if(cval.isNumeric) {
                val copy = LiteralValue(cval.type, cval.bytevalue, cval.wordvalue, cval.floatvalue, null, cval.arrayvalue, position = identifier.position)
                copy.parent = identifier.parent
                copy
            } else
                identifier
        } catch (ax: AstException) {
            addError(ax)
            identifier
        }
    }

    override fun process(functionCall: FunctionCall): IExpression {
        return try {
            super.process(functionCall)
            typeCastConstArguments(functionCall)
            functionCall.constValue(program) ?: functionCall
        } catch (ax: AstException) {
            addError(ax)
            functionCall
        }
    }

    override fun process(functionCallStatement: FunctionCallStatement): IStatement {
        super.process(functionCallStatement)
        typeCastConstArguments(functionCallStatement)
        return functionCallStatement
    }

    private fun typeCastConstArguments(functionCall: IFunctionCall) {
        val subroutine = functionCall.target.targetSubroutine(program.namespace)
        if(subroutine!=null) {
            // if types differ, try to typecast constant arguments to the function call to the desired data type of the parameter
            for(arg in functionCall.arglist.withIndex().zip(subroutine.parameters)) {
                val expectedDt = arg.second.type
                val argConst = arg.first.value.constValue(program)
                if(argConst!=null && argConst.type!=expectedDt) {
                    val convertedValue = argConst.cast(expectedDt)
                    if(convertedValue!=null) {
                        functionCall.arglist[arg.first.index] = convertedValue
                        optimizationsDone++
                    }
                }
            }
        }
    }

    override fun process(memread: DirectMemoryRead): IExpression {
        // @( &thing )  -->  thing
        val addrOf = memread.addressExpression as? AddressOf
        if(addrOf!=null)
            return super.process(addrOf.identifier)
        return super.process(memread)
    }

    /**
     * Try to process a unary prefix expression.
     * Compile-time constant sub expressions will be evaluated on the spot.
     * For instance, the expression for "- 4.5" will be optimized into the float literal -4.5
     */
    override fun process(expr: PrefixExpression): IExpression {
        return try {
            super.process(expr)

            val subexpr = expr.expression
            if (subexpr is LiteralValue) {
                // process prefixed literal values (such as -3, not true)
                return when {
                    expr.operator == "+" -> subexpr
                    expr.operator == "-" -> when {
                        subexpr.asIntegerValue!= null -> {
                            optimizationsDone++
                            LiteralValue.optimalNumeric(-subexpr.asIntegerValue, subexpr.position)
                        }
                        subexpr.floatvalue != null -> {
                            optimizationsDone++
                            LiteralValue(DataType.FLOAT, floatvalue = -subexpr.floatvalue, position = subexpr.position)
                        }
                        else -> throw ExpressionError("can only take negative of int or float", subexpr.position)
                    }
                    expr.operator == "~" -> when {
                        subexpr.asIntegerValue != null -> {
                            optimizationsDone++
                            LiteralValue.optimalNumeric(subexpr.asIntegerValue.inv(), subexpr.position)
                        }
                        else -> throw ExpressionError("can only take bitwise inversion of int", subexpr.position)
                    }
                    expr.operator == "not" -> when {
                        subexpr.asIntegerValue != null -> {
                            optimizationsDone++
                            LiteralValue.fromBoolean(subexpr.asIntegerValue == 0, subexpr.position)
                        }
                        subexpr.floatvalue != null -> {
                            optimizationsDone++
                            LiteralValue.fromBoolean(subexpr.floatvalue == 0.0, subexpr.position)
                        }
                        else -> throw ExpressionError("can not take logical not of $subexpr", subexpr.position)
                    }
                    else -> throw ExpressionError(expr.operator, subexpr.position)
                }
            }
            return expr
        } catch (ax: AstException) {
            addError(ax)
            expr
        }
    }

    /**
     * Try to process a binary expression.
     * Compile-time constant sub expressions will be evaluated on the spot.
     * For instance, "9 * (4 + 2)" will be optimized into the integer literal 54.
     *
     * More complex stuff: reordering to group constants:
     * If one of our operands is a Constant,
     *   and the other operand is a Binary expression,
     *   and one of ITS operands is a Constant,
     *   and ITS other operand is NOT a Constant,
     *   ...it may be possible to rewrite the expression to group the two Constants together,
     *      to allow them to be const-folded away.
     *
     *  examples include:
     *        (X / c1) * c2  ->  X / (c2/c1)
     *        (X + c1) - c2  ->  X + (c1-c2)
     */
    override fun process(expr: BinaryExpression): IExpression {
        return try {
            super.process(expr)
            val leftconst = expr.left.constValue(program)
            val rightconst = expr.right.constValue(program)

            val subExpr: BinaryExpression? = when {
                leftconst!=null -> expr.right as? BinaryExpression
                rightconst!=null -> expr.left as? BinaryExpression
                else -> null
            }
            if(subExpr!=null) {
                val subleftconst = subExpr.left.constValue(program)
                val subrightconst = subExpr.right.constValue(program)
                if ((subleftconst != null && subrightconst == null) || (subleftconst==null && subrightconst!=null)) {
                    // try reordering.
                    return groupTwoConstsTogether(expr, subExpr,
                            leftconst != null, rightconst != null,
                            subleftconst != null, subrightconst != null)
                }
            }

            // const fold when both operands are a const
            val evaluator = ConstExprEvaluator()
            return when {
                leftconst != null && rightconst != null -> {
                    optimizationsDone++
                    evaluator.evaluate(leftconst, expr.operator, rightconst)
                }
                else -> expr
            }
        } catch (ax: AstException) {
            addError(ax)
            expr
        }
    }

    private fun groupTwoConstsTogether(expr: BinaryExpression,
                                       subExpr: BinaryExpression,
                                       leftIsConst: Boolean,
                                       rightIsConst: Boolean,
                                       subleftIsConst: Boolean,
                                       subrightIsConst: Boolean): IExpression
    {
        // @todo this implements only a small set of possible reorderings for now
        if(expr.operator==subExpr.operator) {
            // both operators are the isSameAs.
            // If + or *,  we can simply swap the const of expr and Var in subexpr.
            if(expr.operator=="+" || expr.operator=="*") {
                if(leftIsConst) {
                    if(subleftIsConst)
                        expr.left = subExpr.right.also { subExpr.right = expr.left }
                    else
                        expr.left = subExpr.left.also { subExpr.left = expr.left }
                } else {
                    if(subleftIsConst)
                        expr.right = subExpr.right.also {subExpr.right = expr.right }
                    else
                        expr.right = subExpr.left.also { subExpr.left = expr.right }
                }
                optimizationsDone++
                return expr
            }

            // If - or /,  we simetimes must reorder more, and flip operators (- -> +, / -> *)
            if(expr.operator=="-" || expr.operator=="/") {
                optimizationsDone++
                if(leftIsConst) {
                    return if(subleftIsConst) {
                        val tmp = subExpr.right
                        subExpr.right = subExpr.left
                        subExpr.left = expr.left
                        expr.left = tmp
                        expr.operator = if(expr.operator=="-") "+" else "*"
                        expr
                    } else
                        BinaryExpression(
                                BinaryExpression(expr.left, if (expr.operator == "-") "+" else "*", subExpr.right, subExpr.position),
                                expr.operator, subExpr.left, expr.position)
                } else {
                    return if(subleftIsConst) {
                        expr.right = subExpr.right.also { subExpr.right = expr.right }
                        expr
                    } else
                        BinaryExpression(
                                subExpr.left, expr.operator,
                                BinaryExpression(expr.right, if (expr.operator == "-") "+" else "*", subExpr.right, subExpr.position),
                                expr.position)
                }
            }
            return expr

        }
        else
        {

            if(expr.operator=="/" && subExpr.operator=="*") {
                optimizationsDone++
                if(leftIsConst) {
                    return if(subleftIsConst) {
                        // C1/(C2*V) -> (C1/C2)/V
                        BinaryExpression(
                                BinaryExpression(expr.left, "/", subExpr.left, subExpr.position),
                                "/",
                                subExpr.right, expr.position)
                    } else {
                        // C1/(V*C2) -> (C1/C2)/V
                        BinaryExpression(
                                BinaryExpression(expr.left, "/", subExpr.right, subExpr.position),
                                "/",
                                subExpr.left, expr.position)
                    }
                } else {
                    return if(subleftIsConst) {
                        // (C1*V)/C2 -> (C1/C2)*V
                        BinaryExpression(
                                BinaryExpression(subExpr.left, "/", expr.right, subExpr.position),
                                "*",
                                subExpr.right, expr.position)
                    } else {
                        // (V*C1)/C2 -> (C1/C2)*V
                        BinaryExpression(
                                BinaryExpression(subExpr.right, "/", expr.right, subExpr.position),
                                "*",
                                subExpr.left, expr.position)
                    }
                }
            }
            else if(expr.operator=="*" && subExpr.operator=="/") {
                optimizationsDone++
                if(leftIsConst) {
                    return if(subleftIsConst) {
                        // C1*(C2/V) -> (C1*C2)/V
                        BinaryExpression(
                                BinaryExpression(expr.left, "*", subExpr.left, subExpr.position),
                                "/",
                                subExpr.right, expr.position)
                    } else {
                        // C1*(V/C2) -> (C1/C2)*V
                        BinaryExpression(
                                BinaryExpression(expr.left, "/", subExpr.right, subExpr.position),
                                "*",
                                subExpr.left, expr.position)
                    }
                } else {
                    return if(subleftIsConst) {
                        // (C1/V)*C2 -> (C1*C2)/V
                        BinaryExpression(
                                BinaryExpression(subExpr.left, "*", expr.right, subExpr.position),
                                "/",
                                subExpr.right, expr.position)
                    } else {
                        // (V/C1)*C2 -> (C1/C2)*V
                        BinaryExpression(
                                BinaryExpression(expr.right, "/", subExpr.right, subExpr.position),
                                "*",
                                subExpr.left, expr.position)
                    }
                }
            }
            else if(expr.operator=="+" && subExpr.operator=="-") {
                optimizationsDone++
                if(leftIsConst){
                    return if(subleftIsConst){
                        // c1+(c2-v)  ->  (c1+c2)-v
                        BinaryExpression(
                                BinaryExpression(expr.left, "+", subExpr.left, subExpr.position),
                                "-",
                                subExpr.right, expr.position)
                    } else {
                        // c1+(v-c2)  ->  v+(c1-c2)
                        BinaryExpression(
                                BinaryExpression(expr.left, "-", subExpr.right, subExpr.position),
                                "+",
                                subExpr.left, expr.position)
                    }
                } else {
                    return if(subleftIsConst) {
                        // (c1-v)+c2  ->  (c1+c2)-v
                        BinaryExpression(
                                BinaryExpression(subExpr.left, "+", expr.right, subExpr.position),
                                "-",
                                subExpr.right, expr.position)
                    } else {
                        // (v-c1)+c2  ->  v+(c2-c1)
                        BinaryExpression(
                                BinaryExpression(expr.right, "-", subExpr.right, subExpr.position),
                                "+",
                                subExpr.left, expr.position)
                    }
                }
            }
            else if(expr.operator=="-" && subExpr.operator=="+") {
                optimizationsDone++
                if(leftIsConst) {
                    return if(subleftIsConst) {
                        // c1-(c2+v)  ->  (c1-c2)-v
                        BinaryExpression(
                                BinaryExpression(expr.left, "-", subExpr.left, subExpr.position),
                                "-",
                                subExpr.right, expr.position)
                    } else {
                        // c1-(v+c2)  ->  (c1-c2)-v
                        BinaryExpression(
                                BinaryExpression(expr.left, "-", subExpr.right, subExpr.position),
                                "-",
                                subExpr.left, expr.position)
                    }
                } else {
                    return if(subleftIsConst) {
                        // (c1+v)-c2  ->  v+(c1-c2)
                        BinaryExpression(
                                BinaryExpression(subExpr.left, "-", expr.right, subExpr.position),
                                "+",
                                subExpr.right, expr.position)
                    } else {
                        // (v+c1)-c2  ->  v+(c1-c2)
                        BinaryExpression(
                                BinaryExpression(subExpr.right, "-", expr.right, subExpr.position),
                                "+",
                                subExpr.left, expr.position)
                    }
                }
            }

            return expr
        }
    }

    override fun process(forLoop: ForLoop): IStatement {

        fun adjustRangeDt(rangeFrom: LiteralValue, targetDt: DataType, rangeTo: LiteralValue, stepLiteral: LiteralValue?, range: RangeExpr): RangeExpr {
            val newFrom = rangeFrom.cast(targetDt)
            val newTo = rangeTo.cast(targetDt)
            if (newFrom != null && newTo != null) {
                val newStep: IExpression =
                        if (stepLiteral != null) (stepLiteral.cast(targetDt) ?: stepLiteral) else range.step
                return RangeExpr(newFrom, newTo, newStep, range.position)
            }
            return range
        }

        // adjust the datatype of a range expression in for loops to the loop variable.
        val resultStmt = super.process(forLoop) as ForLoop
        val iterableRange = resultStmt.iterable as? RangeExpr ?: return resultStmt
        val rangeFrom = iterableRange.from as? LiteralValue
        val rangeTo = iterableRange.to as? LiteralValue
        if(rangeFrom==null || rangeTo==null) return resultStmt

        val loopvar = resultStmt.loopVar?.targetVarDecl(program.namespace)
        if(loopvar!=null) {
            val stepLiteral = iterableRange.step as? LiteralValue
            when(loopvar.datatype) {
                DataType.UBYTE -> {
                    if(rangeFrom.type!= DataType.UBYTE) {
                        // attempt to translate the iterable into ubyte values
                        resultStmt.iterable = adjustRangeDt(rangeFrom, loopvar.datatype, rangeTo, stepLiteral, iterableRange)
                    }
                }
                DataType.BYTE -> {
                    if(rangeFrom.type!= DataType.BYTE) {
                        // attempt to translate the iterable into byte values
                        resultStmt.iterable = adjustRangeDt(rangeFrom, loopvar.datatype, rangeTo, stepLiteral, iterableRange)
                    }
                }
                DataType.UWORD -> {
                    if(rangeFrom.type!= DataType.UWORD) {
                        // attempt to translate the iterable into uword values
                        resultStmt.iterable = adjustRangeDt(rangeFrom, loopvar.datatype, rangeTo, stepLiteral, iterableRange)
                    }
                }
                DataType.WORD -> {
                    if(rangeFrom.type!= DataType.WORD) {
                        // attempt to translate the iterable into word values
                        resultStmt.iterable = adjustRangeDt(rangeFrom, loopvar.datatype, rangeTo, stepLiteral, iterableRange)
                    }
                }
                else -> throw FatalAstException("invalid loopvar datatype $loopvar")
            }
        }
        return resultStmt
    }

    override fun process(literalValue: LiteralValue): LiteralValue {
        val litval = super.process(literalValue)
        if(litval.isString) {
            // intern the string; move it into the heap
            if(litval.strvalue!!.length !in 1..255)
                addError(ExpressionError("string literal length must be between 1 and 255", litval.position))
            else {
                litval.addToHeap(program.heap)  // TODO: we don't know the actual string type yet, STR != STR_S etc...
            }
        } else if(litval.arrayvalue!=null) {
            // first, adjust the array datatype
            val litval2 = adjustArrayValDatatype(litval)
            litval2.addToHeap(program.heap)
            return litval2
        }
        return litval
    }

    private fun adjustArrayValDatatype(litval: LiteralValue): LiteralValue {
        val array = litval.arrayvalue!!
        val typesInArray = array.mapNotNull { it.inferType(program) }.toSet()
        val arrayDt =
                when {
                    array.any { it is AddressOf } -> DataType.ARRAY_UW
                    DataType.FLOAT in typesInArray -> DataType.ARRAY_F
                    DataType.WORD in typesInArray -> DataType.ARRAY_W
                    else -> {
                        val allElementsAreConstantOrAddressOf = array.fold(true) { c, expr-> c and (expr is LiteralValue || expr is AddressOf)}
                        if(!allElementsAreConstantOrAddressOf) {
                            addError(ExpressionError("array literal can only consist of constant primitive numerical values or memory pointers", litval.position))
                            return litval
                        } else {
                            val integerArray = array.map { it.constValue(program)!!.asIntegerValue!! }
                            val maxValue = integerArray.max()!!
                            val minValue = integerArray.min()!!
                            if (minValue >= 0) {
                                // unsigned
                                if (maxValue <= 255)
                                    DataType.ARRAY_UB
                                else
                                    DataType.ARRAY_UW
                            } else {
                                // signed
                                if (maxValue <= 127)
                                    DataType.ARRAY_B
                                else
                                    DataType.ARRAY_W
                            }
                        }
                    }
                }

        if(arrayDt!=litval.type) {
            return LiteralValue(arrayDt, arrayvalue = litval.arrayvalue, position = litval.position)
        }
        return litval
    }

    override fun process(assignment: Assignment): IStatement {
        super.process(assignment)
        val lv = assignment.value as? LiteralValue
        if(lv!=null) {
            // see if we can promote/convert a literal value to the required datatype
            when(assignment.singleTarget?.inferType(program, assignment)) {
                DataType.UWORD -> {
                    // we can convert to UWORD: any UBYTE, BYTE/WORD that are >=0, FLOAT that's an integer 0..65535,
                    if(lv.type== DataType.UBYTE)
                        assignment.value = LiteralValue(DataType.UWORD, wordvalue = lv.asIntegerValue, position = lv.position)
                    else if(lv.type== DataType.BYTE && lv.bytevalue!!>=0)
                        assignment.value = LiteralValue(DataType.UWORD, wordvalue = lv.asIntegerValue, position = lv.position)
                    else if(lv.type== DataType.WORD && lv.wordvalue!!>=0)
                        assignment.value = LiteralValue(DataType.UWORD, wordvalue = lv.asIntegerValue, position = lv.position)
                    else if(lv.type== DataType.FLOAT) {
                        val d = lv.floatvalue!!
                        if(floor(d)==d && d>=0 && d<=65535)
                            assignment.value = LiteralValue(DataType.UWORD, wordvalue = floor(d).toInt(), position = lv.position)
                    }
                }
                DataType.UBYTE -> {
                    // we can convert to UBYTE: UWORD <=255, BYTE >=0, FLOAT that's an integer 0..255,
                    if(lv.type== DataType.UWORD && lv.wordvalue!! <= 255)
                        assignment.value = LiteralValue(DataType.UBYTE, lv.wordvalue.toShort(), position = lv.position)
                    else if(lv.type== DataType.BYTE && lv.bytevalue!! >=0)
                        assignment.value = LiteralValue(DataType.UBYTE, lv.bytevalue.toShort(), position = lv.position)
                    else if(lv.type== DataType.FLOAT) {
                        val d = lv.floatvalue!!
                        if(floor(d)==d && d >=0 && d<=255)
                            assignment.value = LiteralValue(DataType.UBYTE, floor(d).toShort(), position = lv.position)
                    }
                }
                DataType.BYTE -> {
                    // we can convert to BYTE: UWORD/UBYTE <= 127, FLOAT that's an integer 0..127
                    if(lv.type== DataType.UWORD && lv.wordvalue!! <= 127)
                        assignment.value = LiteralValue(DataType.BYTE, lv.wordvalue.toShort(), position = lv.position)
                    else if(lv.type== DataType.UBYTE && lv.bytevalue!! <= 127)
                        assignment.value = LiteralValue(DataType.BYTE, lv.bytevalue, position = lv.position)
                    else if(lv.type== DataType.FLOAT) {
                        val d = lv.floatvalue!!
                        if(floor(d)==d && d>=0 && d<=127)
                            assignment.value = LiteralValue(DataType.BYTE, floor(d).toShort(), position = lv.position)
                    }
                }
                DataType.WORD -> {
                    // we can convert to WORD: any UBYTE/BYTE, UWORD <= 32767, FLOAT that's an integer -32768..32767,
                    if(lv.type== DataType.UBYTE || lv.type== DataType.BYTE)
                        assignment.value = LiteralValue(DataType.WORD, wordvalue = lv.bytevalue!!.toInt(), position = lv.position)
                    else if(lv.type== DataType.UWORD && lv.wordvalue!! <= 32767)
                        assignment.value = LiteralValue(DataType.WORD, wordvalue = lv.wordvalue, position = lv.position)
                    else if(lv.type== DataType.FLOAT) {
                        val d = lv.floatvalue!!
                        if(floor(d)==d && d>=-32768 && d<=32767)
                            assignment.value = LiteralValue(DataType.BYTE, floor(d).toShort(), position = lv.position)
                    }
                }
                DataType.FLOAT -> {
                    if(lv.isNumeric)
                        assignment.value = LiteralValue(DataType.FLOAT, floatvalue = lv.asNumericValue?.toDouble(), position = lv.position)
                }
                else -> {}
            }
        }
        return assignment
    }
}