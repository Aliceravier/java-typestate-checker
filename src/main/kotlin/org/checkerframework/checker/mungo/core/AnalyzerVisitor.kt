package org.checkerframework.checker.mungo.core

import com.sun.source.tree.*
import com.sun.source.util.TreePath
import com.sun.tools.javac.code.Symbol
import org.checkerframework.checker.mungo.typecheck.MungoBottomType
import org.checkerframework.checker.mungo.typecheck.MungoMovedType
import org.checkerframework.checker.mungo.typecheck.MungoTypecheck
import org.checkerframework.checker.mungo.utils.MethodUtils
import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.dataflow.cfg.ControlFlowGraph
import org.checkerframework.dataflow.cfg.UnderlyingAST
import org.checkerframework.dataflow.cfg.node.*
import org.checkerframework.javacutil.TreeUtils
import javax.lang.model.element.ExecutableElement
import javax.lang.model.type.TypeMirror

class AnalyzerVisitor(private val checker: MainChecker, private val analyzer: Analyzer) : AbstractNodeVisitor<Void?, MutableAnalyzerResultWithValue>() {

  private val utils get() = checker.utils

  private val allLabels: (String) -> Boolean = { true }
  private val ifTrue: (String) -> Boolean = { it == "true" }
  private val ifFalse: (String) -> Boolean = { it == "false" }

  private var root: CompilationUnitTree? = null

  fun setRoot(root: CompilationUnitTree) {
    this.root = root
  }

  private fun getPath(tree: Tree) = utils.getPath(tree, root!!)

  fun initialStore(capturedStore: Store, cfg: ControlFlowGraph): Store {
    val parameters = when (val ast = cfg.underlyingAST) {
      is UnderlyingAST.CFGMethod -> ast.method.parameters.map { LocalVariableNode(it) }
      is UnderlyingAST.CFGLambda -> ast.lambdaTree.parameters.map { LocalVariableNode(it) }
      else -> null
    }

    val store = capturedStore.toMutable()

    parameters?.forEach {
      val internal = getReference(it)!!
      val mungoType = MungoTypecheck.typeDeclaration(utils, it.type)
      store[internal] = StoreInfo(mungoType, it.type)
    }

    return store.toImmutable()
  }

  private fun getCurrentInfo(res: MutableAnalyzerResultWithValue, node: Node): StoreInfo? {
    // TODO what todo when there is no info? Is it unknown? Bottom?
    return getReference(node)?.let { res.getInfo(it) }
  }

  private fun setCurrentInfo(res: MutableAnalyzerResultWithValue, node: Node, info: StoreInfo) {
    getReference(node)?.let {
      res.thenStore[it] = info
      res.elseStore[it] = info
    }
  }

  private fun refineStore(invocation: MethodInvocationNode, method: Symbol.MethodSymbol, receiver: Reference, store: MutableStore, predicate: (String) -> Boolean) {
    val prevValue = analyzer.getInferredInfo(invocation.target.receiver)
    val prevInfo = prevValue.mungoType
    val newInfo = MungoTypecheck.refine(utils, invocation.treePath, prevInfo, method, predicate)
    store[receiver] = StoreInfo(prevValue, newInfo)
  }

  private fun refineStoreMore(invocation: MethodInvocationNode, method: Symbol.MethodSymbol, receiver: Reference, store: MutableStore, predicate: (String) -> Boolean) {
    val prevValue = analyzer.getInferredInfo(invocation.target.receiver)
    val prevInfo = prevValue.mungoType
    val newInfo = MungoTypecheck.refine(utils, invocation.treePath, prevInfo, method, predicate)
    // We are refining a switch case or an expression after the method invocation was done,
    // so intersect with the old information.
    store.intersect(receiver, StoreInfo(prevValue, newInfo))
  }

  override fun visitNode(n: Node, result: MutableAnalyzerResultWithValue): Void? {
    return null
  }

  override fun visitClassName(n: ClassNameNode, input: MutableAnalyzerResultWithValue): Void? {
    // The tree underlying a class name is a type tree.
    /*val tree = n.tree
    if (tree != null) {
      if (TreeUtils.canHaveTypeAnnotation(tree)) {
        // val at = factory.getAnnotatedTypeFromTypeTree(tree)
        // TODO
        return MutableAnalyzerResult(getInitialInfo(tree, n), input.thenStore, input.elseStore)
      }
    }
    return MutableAnalyzerResult(null, input.thenStore, input.elseStore)*/
    return null
  }

  /*fun visitArrayAccess(n: ArrayAccessNode, res: MutableAnalyzerResult): MutableAnalyzerResult {
    return super.visitArrayAccess(n, res)
  }*/

  override fun visitThisLiteral(n: ThisLiteralNode, res: MutableAnalyzerResultWithValue): Void? {
    getCurrentInfo(res, n)?.let { res.value = it }
    return null
  }

  override fun visitTernaryExpression(n: TernaryExpressionNode, res: MutableAnalyzerResultWithValue): Void? {
    val thenValue = analyzer.getInferredInfo(n.thenOperand)
    val elseValue = analyzer.getInferredInfo(n.elseOperand)
    res.value = StoreInfo.merge(thenValue, elseValue)
    return null
  }

  override fun visitConditionalNot(n: ConditionalNotNode, res: MutableAnalyzerResultWithValue): Void? {
    refineCondition(n, res)
    return null
  }

  override fun visitBooleanLiteral(n: BooleanLiteralNode, res: MutableAnalyzerResultWithValue): Void? {
    refineCondition(n, res)
    return null
  }

  override fun visitEqualTo(n: EqualToNode, res: MutableAnalyzerResultWithValue): Void? {
    val leftN = n.leftOperand
    val rightN = n.rightOperand
    val leftV = analyzer.getInferredInfo(leftN)
    val rightV = analyzer.getInferredInfo(rightN)

    // if annotations differ, use the one that is more precise for both
    // sides (and add it to the store if possible)
    strengthenAnnotationOfEqualTo(res, leftN, rightN, leftV, rightV, false)
    strengthenAnnotationOfEqualTo(res, rightN, leftN, rightV, leftV, false)
    return null
  }

  override fun visitNotEqual(n: NotEqualNode, res: MutableAnalyzerResultWithValue): Void? {
    val leftN = n.leftOperand
    val rightN = n.rightOperand
    val leftV = analyzer.getInferredInfo(leftN)
    val rightV = analyzer.getInferredInfo(rightN)

    // if annotations differ, use the one that is more precise for both
    // sides (and add it to the store if possible)
    strengthenAnnotationOfEqualTo(res, leftN, rightN, leftV, rightV, true)
    strengthenAnnotationOfEqualTo(res, rightN, leftN, rightV, leftV, true)
    return null
  }

  private fun strengthenAnnotationOfEqualTo(
    result: MutableAnalyzerResultWithValue,
    firstNode: Node,
    secondNode: Node,
    firstValue: StoreInfo,
    secondValue: StoreInfo,
    notEqualTo: Boolean
  ) {
    val thenStore = result.thenStore
    val elseStore = result.elseStore

    if (firstValue != null) {
      // Only need to insert if the second value is actually different.
      if (firstValue != secondValue) {
        val secondParts = splitAssignments(secondNode)
        for (secondPart in secondParts) {
          val secondInternal = getReference(secondPart)
          if (secondInternal != null) {
            if (notEqualTo) {
              elseStore[secondInternal] = firstValue
            } else {
              thenStore[secondInternal] = firstValue
            }
          }
        }
      }
    }

    if (firstNode is NullLiteralNode) {
      refineOfEqualToNull(result, secondNode, secondValue, !notEqualTo)
    } else {
      refineEqualTo(result, firstNode, secondNode, !notEqualTo)
    }
  }

  private fun splitAssignments(node: Node): List<Node> {
    return if (node is AssignmentNode) {
      val result = mutableListOf<Node>()
      result.add(node.target)
      result.addAll(splitAssignments(node.expression))
      result
    } else {
      listOf(node)
    }
  }

  override fun visitAssignment(n: AssignmentNode, result: MutableAnalyzerResultWithValue): Void? {
    val lhs = n.target
    val rhs = n.expression
    val rhsValue = analyzer.getInferredInfo(rhs)
    setCurrentInfo(result, lhs, rhsValue)

    // Restore the type of the receiver
    val target = n.target
    if (target is FieldAccessNode) {
      setCurrentInfo(result, target.receiver, analyzer.getInferredInfo(target.receiver))
    }

    // Handle move in assignment
    handleMove(n.expression, result)

    return null
  }

  override fun visitReturn(n: ReturnNode, result: MutableAnalyzerResultWithValue): Void? {
    n.result?.let { handleMove(it, result) }
    return null
  }

  // Mark receiver of method access as moved so that it cannot be used in the arguments
  // "handleMove" only changes the store, the value of the expression remains the same,
  // so it will not affect the "refineStore" and "refineStoreMore" operations
  override fun visitMethodAccess(n: MethodAccessNode, result: MutableAnalyzerResultWithValue): Void? {
    handleMove(n.receiver, result)
    return null
  }

  private fun handleMove(initialNode: Node, result: MutableAnalyzerResultWithValue) {
    var node = initialNode
    while (node is TypeCastNode) {
      node = node.operand
    }
    if (node is LocalVariableNode || node is FieldAccessNode) {
      val value = getCurrentInfo(result, node) ?: return
      val type = value.mungoType
      if (!type.isSubtype(MungoTypecheck.noProtocolOrMoved)) {
        setCurrentInfo(result, node, StoreInfo(value, MungoMovedType.SINGLETON))
      }
    }
  }

  override fun visitLambdaResultExpression(n: LambdaResultExpressionNode, input: MutableAnalyzerResultWithValue): Void? {
    // n.result!!.accept(this, input)
    return null
  }

  override fun visitStringConcatenateAssignment(n: StringConcatenateAssignmentNode, res: MutableAnalyzerResultWithValue): Void? {
    setCurrentInfo(res, n.leftOperand, res.value)
    return null
  }

  override fun visitObjectCreation(node: ObjectCreationNode, result: MutableAnalyzerResultWithValue): Void? {
    // Handle moves
    processMethodArguments(node.arguments, TreeUtils.elementFromUse(node.tree)!!, result)

    // Refine result value to the initial state
    result.value = StoreInfo(result.value, MungoTypecheck.objectCreation(utils, result.value.type))
    return null
  }

  override fun visitMethodInvocation(n: MethodInvocationNode, result: MutableAnalyzerResultWithValue): Void? {
    // Invalidate
    // TODO improve
    result.thenStore.invalidate(utils)
    result.elseStore.invalidate(utils)

    val method = n.target.method as Symbol.MethodSymbol
    val args = n.arguments

    // Apply type refinements

    val receiver = getReference(n.target.receiver) ?: return null
    val returnsBool = MethodUtils.returnsBoolean(method)
    val predicateThen = if (returnsBool) ifTrue else allLabels
    val predicateElse = if (returnsBool) ifFalse else allLabels

    val thenStore = result.thenStore
    val elseStore = result.elseStore
    refineStore(n, method, receiver, thenStore, predicateThen)
    refineStore(n, method, receiver, elseStore, predicateElse)
    // Handle moves/post-conditions
    processMethodArguments(args, method, result)
    return null
  }

  private fun processMethodArguments(arguments: List<Node>, method: ExecutableElement, result: MutableAnalyzerResultWithValue) {
    val paramsIt = method.parameters.iterator()
    arguments.forEach {
      val typeMirror = paramsIt.next().asType()
      handlePostCondition(it, result, typeMirror)
    }
  }

  private fun handlePostCondition(initialNode: Node, result: MutableAnalyzerResultWithValue, typeMirror: TypeMirror) {
    val newType = MungoTypecheck.typeAfterMethodCall(utils, typeMirror) ?: return

    var node = initialNode
    while (node is TypeCastNode) {
      node = node.operand
    }
    if (node is LocalVariableNode || node is FieldAccessNode) {
      val value = getCurrentInfo(result, node) ?: return
      val newValue = StoreInfo(value, newType)
      setCurrentInfo(result, node, newValue)
    }
  }

  override fun visitCase(n: CaseNode, result: MutableAnalyzerResultWithValue): Void? {
    val caseValue = analyzer.getInferredInfo(n.caseOperand)
    val assign = n.switchOperand as AssignmentNode
    val switchValue = getCurrentInfo(result, assign.target) ?: return null
    strengthenAnnotationOfEqualTo(
      result,
      n.caseOperand,
      assign.expression,
      caseValue,
      switchValue,
      false)

    // Update value of switch temporary variable
    strengthenAnnotationOfEqualTo(
      result,
      n.caseOperand,
      assign.target,
      caseValue,
      switchValue,
      false)
    return null
  }

  // Adapted from NullnessTransfer#visitInstanceOf
  override fun visitInstanceOf(n: InstanceOfNode, result: MutableAnalyzerResultWithValue): Void? {
    val prevValue = getCurrentInfo(result, n.operand) ?: return null
    val internal = getReference(n.operand) ?: return null
    val thenStore = result.thenStore
    thenStore[internal] = StoreInfo(prevValue, MungoTypecheck.refineToNonNull(prevValue.mungoType))
    return null
  }

  override fun visitVariableDeclaration(n: VariableDeclarationNode, result: MutableAnalyzerResultWithValue): Void? {
    // Make sure the entry for this variable is clear
    val internal = getReference(LocalVariableNode(n.tree)) ?: return null
    result.thenStore.remove(internal)
    result.elseStore.remove(internal)
    return null
  }

  override fun visitNarrowingConversion(n: NarrowingConversionNode, result: MutableAnalyzerResultWithValue): Void? {
    result.value = analyzer.getInferredInfo(n.operand)
    return null
  }

  override fun visitWideningConversion(n: WideningConversionNode, result: MutableAnalyzerResultWithValue): Void? {
    result.value = analyzer.getInferredInfo(n.operand)
    return null
  }

  override fun visitStringConversion(n: StringConversionNode, result: MutableAnalyzerResultWithValue): Void? {
    result.value = analyzer.getInferredInfo(n.operand)
    return null
  }

  override fun visitLocalVariable(n: LocalVariableNode, result: MutableAnalyzerResultWithValue): Void? {
    val value = getCurrentInfo(result, n) ?: return null
    if (wasMovedToClosure(n)) {
      // We will error when it detects a moved variable
      // So refine to bottom to avoid duplicate errors
      val newValue = StoreInfo(value, MungoBottomType.SINGLETON)
      result.value = newValue
      setCurrentInfo(result, n, newValue)
    } else {
      // Prefer the inferred type information
      result.value = value
    }

    if (isAssignmentReceiver(n) || isParameter(n)) {
      handleMove(n, result)
    }
    return null
  }

  override fun visitFieldAccess(n: FieldAccessNode, result: MutableAnalyzerResultWithValue): Void? {
    val value = getCurrentInfo(result, n) ?: return null
    // Prefer the inferred type information
    result.value = value

    if (isAssignmentReceiver(n) || isParameter(n)) {
      handleMove(n, result)
    }
    return null
  }

  override fun visitTypeCast(n: TypeCastNode, result: MutableAnalyzerResultWithValue): Void? {
    result.value = StoreInfo(result.value, MungoTypecheck.typeDeclaration(utils, n.type))
    return null
  }

  private fun getBooleanValue(node: Node): Boolean? = when (node) {
    is BooleanLiteralNode -> node.value!!
    is ConditionalNotNode -> getBooleanValue(node.operand)?.not()
    else -> null
  }

  private fun getLabel(node: Node) = when (node) {
    is FieldAccessNode -> if (MungoUtils.isEnum(node.type)) node.fieldName else null
    else -> getBooleanValue(node)?.let { if (it) "true" else "false" }
  }

  private fun refineCondition(n: Node, res: MutableAnalyzerResultWithValue) {
    if (analyzer.nextIsConditional(n)) {
      val bool = getBooleanValue(n)
      if (bool != null) {
        if (bool) {
          res.elseStore.toBottom()
        } else {
          res.thenStore.toBottom()
        }
      }
    }
  }

  private fun refineOfEqualToNull(res: MutableAnalyzerResultWithValue, secondNode: Node, secondValue: StoreInfo, equalTo: Boolean) {
    // Adapted from NullnessTransfer#strengthenAnnotationOfEqualTo
    val thenStore = res.thenStore
    val elseStore = res.elseStore
    val secondParts = splitAssignments(secondNode)
    for (secondPart in secondParts) {
      val secondInternal = getReference(secondPart)
      if (secondInternal != null) {
        val storeToUpdate = if (equalTo) elseStore else thenStore
        storeToUpdate[secondInternal] = StoreInfo(secondValue, MungoTypecheck.refineToNonNull(secondValue.mungoType))
        val otherStoreToUpdate = if (equalTo) thenStore else elseStore
        otherStoreToUpdate[secondInternal] = StoreInfo(secondValue, MungoTypecheck.refineToNull(secondValue.mungoType))
      }
    }
  }

  private fun refineEqualTo(res: MutableAnalyzerResultWithValue, firstNode: Node, secondNode: Node, originalEqualTo: Boolean) {
    var equalTo = originalEqualTo
    var expression = secondNode

    while (expression is ConditionalNotNode) {
      expression = expression.operand
      equalTo = !equalTo
    }

    if (expression is MethodInvocationNode) {
      val method = expression.target.method as Symbol.MethodSymbol
      val receiver = getReference(expression.target.receiver) ?: return
      val label = getLabel(firstNode)

      if (label != null) {
        val equalLabel = { it: String -> it == label }
        val notEqualLabel = { it: String -> it != label }
        val thenStore = res.thenStore
        val elseStore = res.elseStore
        refineStoreMore(expression, method, receiver, thenStore, if (equalTo) equalLabel else notEqualLabel)
        refineStoreMore(expression, method, receiver, elseStore, if (equalTo) notEqualLabel else equalLabel)
      }
    }
  }

  private fun wasMovedToClosure(n: LocalVariableNode): Boolean {
    val tree = n.tree as? IdentifierTree ?: return false
    val path = getPath(tree) ?: return false
    val element = TreeUtils.elementFromTree(tree) as? Symbol.VarSymbol ?: return false
    return utils.wasMovedToDiffClosure(path, tree, element)
  }

  private fun unwrap(node: Node): Pair<TreePath, TreePath>? {
    var path = node.tree?.let { getPath(it) } ?: return null
    var parent = path.parentPath
    while (parent.leaf is TypeCastTree || parent.leaf is ParenthesizedTree) {
      path = path.parentPath
      parent = path.parentPath
    }
    return Pair(path, parent)
  }

  private fun isParameter(node: Node): Boolean {
    val (path, parent) = unwrap(node) ?: return false
    val maybeArg = path.leaf
    return when (val maybeCall = parent.leaf) {
      is MethodInvocationTree -> maybeCall.arguments.contains(maybeArg)
      is NewClassTree -> maybeCall.arguments.contains(maybeArg)
      else -> false
    }
  }

  private fun isAssignmentReceiver(node: Node): Boolean {
    val (path, parent) = unwrap(node) ?: return false
    val maybeReceiver = path.leaf
    val maybeField = parent.leaf
    val maybeLeft = parent.parentPath.leaf
    return maybeField is MemberSelectTree && maybeField.expression === maybeReceiver &&
      maybeLeft is AssignmentTree && maybeLeft.variable === maybeField
  }

}
