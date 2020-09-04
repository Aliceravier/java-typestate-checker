package org.checkerframework.checker.mungo.analysis

import com.sun.source.tree.*
import com.sun.source.util.TreePath
import com.sun.tools.javac.code.Symbol
import org.checkerframework.checker.mungo.old.MungoChecker
import org.checkerframework.checker.mungo.typecheck.MungoMovedType
import org.checkerframework.checker.mungo.typecheck.MungoNullType
import org.checkerframework.checker.mungo.typecheck.MungoTypecheck
import org.checkerframework.checker.mungo.utils.MethodUtils
import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.dataflow.analysis.*
import org.checkerframework.dataflow.cfg.UnderlyingAST
import org.checkerframework.dataflow.cfg.node.*
import org.checkerframework.framework.flow.CFAbstractStore
import org.checkerframework.framework.flow.CFAbstractTransfer
import org.checkerframework.javacutil.TreeUtils
import javax.lang.model.element.ExecutableElement
import javax.lang.model.type.TypeMirror

class MungoTransfer(checker: MungoChecker, analysis: MungoAnalysis) : CFAbstractTransfer<MungoValue, MungoStore, MungoTransfer>(analysis) {

  private val c = checker
  private val a = analysis
  private val utils get() = c.utils

  private val allLabels: (String) -> Boolean = { true }
  private val ifTrue: (String) -> Boolean = { it == "true" }
  private val ifFalse: (String) -> Boolean = { it == "false" }

  // Returns true if store changed
  private fun refineStore(invocation: MethodInvocationNode, method: Symbol.MethodSymbol, receiver: FlowExpressions.Receiver, store: MungoStore, predicate: (String) -> Boolean): Boolean {
    val prevValue = a.getValue(invocation.target.receiver) ?: return false
    val prevInfo = prevValue.info
    val newInfo = MungoTypecheck.refine(utils, invocation.treePath, prevInfo, method, predicate)
    return store.replaceValueIfDiff(receiver, MungoValue(prevValue, newInfo))
  }

  // Returns true if store changed
  private fun refineStoreMore(invocation: MethodInvocationNode, method: Symbol.MethodSymbol, receiver: FlowExpressions.Receiver, store: MungoStore, predicate: (String) -> Boolean): Boolean {
    val prevValue = a.getValue(invocation.target.receiver) ?: return false
    val prevInfo = prevValue.info
    val newInfo = MungoTypecheck.refine(utils, invocation.treePath, prevInfo, method, predicate)
    // We are refining a switch case or an expression after the method invocation was done,
    // so intersect with the old information.
    return store.intersectValueIfDiff(receiver, MungoValue(prevValue, newInfo))
  }

  override fun visitMethodInvocation(n: MethodInvocationNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val result = super.visitMethodInvocation(n, input)
    val method = n.target.method as Symbol.MethodSymbol
    val args = n.arguments

    // Apply type refinements

    val receiver = FlowExpressions.internalReprOf(analysis.typeFactory, n.target.receiver)

    if (MethodUtils.returnsBoolean(method)) {
      val thenStore = result.thenStore
      val elseStore = result.elseStore
      val didChangeThen = refineStore(n, method, receiver, thenStore, ifTrue)
      val didChangeElse = refineStore(n, method, receiver, elseStore, ifFalse)
      // Handle moves/post-conditions
      val argsChange = processMethodArguments(args, method, result)
      return if (argsChange || didChangeThen || didChangeElse) ConditionalTransferResult(result.resultValue, thenStore, elseStore, true) else result
    }

    return if (result.containsTwoStores()) {
      val thenStore = result.thenStore
      val elseStore = result.elseStore
      val didChangeThen = refineStore(n, method, receiver, thenStore, allLabels)
      val didChangeElse = refineStore(n, method, receiver, elseStore, allLabels)
      // Handle moves/post-conditions
      val argsChange = processMethodArguments(args, method, result)
      if (argsChange || didChangeThen || didChangeElse) ConditionalTransferResult(result.resultValue, thenStore, elseStore, true) else result
    } else {
      val store = result.regularStore
      val didChange = refineStore(n, method, receiver, store, allLabels)
      // Handle moves/post-conditions
      val argsChange = processMethodArguments(args, method, result)
      if (argsChange || didChange) RegularTransferResult(result.resultValue, store, true) else result
    }
  }

  private fun processMethodArguments(arguments: List<Node>, method: ExecutableElement, result: TransferResult<MungoValue, MungoStore>): Boolean {
    val paramsIt = method.parameters.iterator()
    return arguments.map {
      val typeMirror = paramsIt.next().asType()
      handlePostCondition(it, result, typeMirror)
    }.any { it }
  }

  override fun visitObjectCreation(node: ObjectCreationNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val result = super.visitObjectCreation(node, input)

    // Handle moves
    val argsChange = processMethodArguments(node.arguments, TreeUtils.elementFromUse(node.tree)!!, result)

    // Refine resultValue to the initial state
    val value = result.resultValue!!
    val newValue = MungoValue(value, MungoTypecheck.objectCreation(utils, value.underlyingType))

    return RegularTransferResult(newValue, result.regularStore, argsChange)
  }

  override fun visitAssignment(n: AssignmentNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val result = super.visitAssignment(n, input)

    // Restore the type of the receiver
    val target = n.target
    val changed = if (target is FieldAccessNode) {
      val value = a.getValue(target.receiver)
      val receiver = FlowExpressions.internalReprOf(analysis.typeFactory, target.receiver)
      setValue(result, receiver, value)
    } else false

    // Handle move in assignment
    val moved = handleMove(n.expression, result)

    return if (changed || moved) newResult(result) else result
  }

  // Handle move in return
  override fun visitReturn(n: ReturnNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val result = super.visitReturn(n, input)
    val moved = n.result?.let { handleMove(it, result) } ?: false
    return if (moved) newResult(result) else result
  }

  // Mark receiver of method access as moved so that it cannot be used in the arguments
  // "handleMove" only changes the store, the value of the expression remains the same,
  // so it will not affect the "refineStore" and "refineStoreMore" operations
  override fun visitMethodAccess(n: MethodAccessNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val result = super.visitMethodAccess(n, input)
    val moved = handleMove(n.receiver, result)
    return if (moved) newResult(result) else result
  }

  // Returns true iff store changed
  private fun handleMove(initialNode: Node, result: TransferResult<MungoValue, MungoStore>): Boolean {
    var node = initialNode
    while (node is TypeCastNode) {
      node = node.operand
    }
    if (node is LocalVariableNode || node is FieldAccessNode) {
      val r = FlowExpressions.internalReprOf(analysis.typeFactory, node)
      val value = result.regularStore.getValue(r)
      val type = value.info
      if (!type.isSubtype(MungoTypecheck.noProtocolOrMoved)) {
        val newValue = MungoValue(value, MungoMovedType.SINGLETON)
        if (result.containsTwoStores()) {
          result.thenStore.replaceValue(r, newValue)
          result.elseStore.replaceValue(r, newValue)
        } else {
          result.regularStore.replaceValue(r, newValue)
        }
        return true
      }
    }
    return false
  }

  // Returns true iff store changed
  private fun handlePostCondition(initialNode: Node, result: TransferResult<MungoValue, MungoStore>, typeMirror: TypeMirror): Boolean {
    val newType = MungoTypecheck.typeAfterMethodCall(utils, typeMirror) ?: return false

    var node = initialNode
    while (node is TypeCastNode) {
      node = node.operand
    }
    if (node is LocalVariableNode || node is FieldAccessNode) {
      val r = FlowExpressions.internalReprOf(analysis.typeFactory, node)
      val newValue = MungoValue(a, newType, typeMirror)
      if (result.containsTwoStores()) {
        result.thenStore.replaceValue(r, newValue)
        result.elseStore.replaceValue(r, newValue)
      } else {
        result.regularStore.replaceValue(r, newValue)
      }
      return true
    }
    return false
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

  private fun refineOfEqualToNull(res: TransferResult<MungoValue, MungoStore>, secondNode: Node, secondValue: MungoValue, equalTo: Boolean): TransferResult<MungoValue, MungoStore> {
    // Adapted from NullnessTransfer#strengthenAnnotationOfEqualTo
    var thenStore = res.thenStore
    var elseStore = res.elseStore
    val secondParts = splitAssignments(secondNode)
    for (secondPart in secondParts) {
      val secondInternal = FlowExpressions.internalReprOf(c.typeFactory, secondPart)
      if (CFAbstractStore.canInsertReceiver(secondInternal)) {
        thenStore = thenStore ?: res.thenStore
        elseStore = elseStore ?: res.elseStore
        val storeToUpdate = if (equalTo) elseStore else thenStore
        storeToUpdate.insertValue(secondInternal, MungoValue(secondValue, MungoTypecheck.refineToNonNull(secondValue.info)))
        val otherStoreToUpdate = if (equalTo) thenStore else elseStore
        otherStoreToUpdate.insertValue(secondInternal, MungoValue(secondValue, MungoTypecheck.refineToNull(secondValue.info)))
      }
    }
    return if (thenStore == null) {
      res
    } else {
      ConditionalTransferResult(res.resultValue, thenStore, elseStore)
    }
  }

  private fun refineEqualTo(res: TransferResult<MungoValue, MungoStore>, firstNode: Node, secondNode: Node, originalEqualTo: Boolean): TransferResult<MungoValue, MungoStore> {
    var equalTo = originalEqualTo
    var expression = secondNode

    while (expression is ConditionalNotNode) {
      expression = expression.operand
      equalTo = !equalTo
    }

    if (expression is MethodInvocationNode) {
      val method = expression.target.method as Symbol.MethodSymbol
      val receiver = FlowExpressions.internalReprOf(analysis.typeFactory, expression.target.receiver)
      val label = getLabel(firstNode)

      if (label != null) {
        val equalLabel = { it: String -> it == label }
        val notEqualLabel = { it: String -> it != label }
        val thenStore = res.thenStore
        val elseStore = res.elseStore
        val didChangeThen = refineStoreMore(expression, method, receiver, thenStore, if (equalTo) equalLabel else notEqualLabel)
        val didChangeElse = refineStoreMore(expression, method, receiver, elseStore, if (equalTo) notEqualLabel else equalLabel)
        return if (didChangeThen || didChangeElse) ConditionalTransferResult(res.resultValue, thenStore, elseStore, true) else res
      }
    }
    return res
  }

  override fun strengthenAnnotationOfEqualTo(res: TransferResult<MungoValue, MungoStore>, firstNode: Node, secondNode: Node, firstValue: MungoValue, secondValue: MungoValue, notEqualTo: Boolean): TransferResult<MungoValue, MungoStore> {
    val result = super.strengthenAnnotationOfEqualTo(res, firstNode, secondNode, firstValue, secondValue, notEqualTo)
    return if (firstNode is NullLiteralNode) {
      refineOfEqualToNull(result, secondNode, secondValue, !notEqualTo)
    } else {
      refineEqualTo(result, firstNode, secondNode, !notEqualTo)
    }
  }

  // Adapted from NullnessTransfer#visitInstanceOf
  override fun visitInstanceOf(n: InstanceOfNode, p: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val result = super.visitInstanceOf(n, p)
    val thenStore = result.thenStore
    val elseStore = result.elseStore

    val internal = FlowExpressions.internalReprOf(c.typeFactory, n.operand)
    val prevValue = thenStore.getValue(internal)
    thenStore.insertValue(internal, MungoValue(prevValue, MungoTypecheck.refineToNonNull(prevValue.info)))

    return ConditionalTransferResult(result.resultValue, thenStore, elseStore)
  }

  override fun visitVariableDeclaration(n: VariableDeclarationNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    // Make sure the entry for this variable is clear
    val store = input.regularStore
    store.clearValue(FlowExpressions.LocalVariable(LocalVariableNode(n.tree)))
    return RegularTransferResult(finishValue(null, store), store)
  }

  // Support while(true) and variants

  private fun refineCondition(n: Node, res: TransferResult<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    if (a.nextIsConditional(n)) {
      val bool = getBooleanValue(n)
      if (bool != null) {
        return if (bool) {
          ConditionalTransferResult(res.resultValue, res.thenStore, res.elseStore.toBottom())
        } else {
          ConditionalTransferResult(res.resultValue, res.thenStore.toBottom(), res.elseStore)
        }
      }
    }
    return res
  }

  override fun visitConditionalNot(n: ConditionalNotNode, p: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    return refineCondition(n, super.visitConditionalNot(n, p))
  }

  override fun visitBooleanLiteral(n: BooleanLiteralNode, p: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    return refineCondition(n, super.visitBooleanLiteral(n, p))
  }

  private fun wasMovedToClosure(n: LocalVariableNode): Boolean {
    // TODO this file will be removed anyway
    return false
  }

  override fun visitLocalVariable(n: LocalVariableNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val store = input.regularStore
    val value = store.getValue(n)
    val result = if (wasMovedToClosure(n)) {
      // MungoVisitor will error when it detects a moved variable
      // So refine to bottom to avoid duplicate errors
      val newValue = value.toBottom()
      store.replaceValue(FlowExpressions.LocalVariable(n), newValue)
      RegularTransferResult(finishValue(newValue, store), store, true)
    } else {
      // Prefer the inferred type information instead of using "mostSpecific" with information in factory
      RegularTransferResult(finishValue(value, store), store)
    }

    if (isAssignmentReceiver(n)) {
      val changed = handleMove(n, result)
      if (changed) return newResult(result)
    }

    if (isParameter(n)) {
      val changed = handleMove(n, result)
      if (changed) return newResult(result)
    }
    return result
  }

  override fun visitFieldAccess(n: FieldAccessNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val store = input.regularStore
    val value = store.getValue(n)
    // Prefer the inferred type information instead of using "mostSpecific" with information in factory
    val result = RegularTransferResult(finishValue(value, store), store)

    if (isAssignmentReceiver(n)) {
      val changed = handleMove(n, result)
      if (changed) return newResult(result)
    }

    if (isParameter(n)) {
      val changed = handleMove(n, result)
      if (changed) return newResult(result)
    }
    return result
  }

  override fun visitTypeCast(n: TypeCastNode, input: TransferInput<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    val store = input.regularStore
    val value = MungoValue(a, MungoTypecheck.typeDeclaration(utils, n.type), n.type)
    return RegularTransferResult(finishValue(value, store), store)
  }

  override fun initialStore(underlyingAST: UnderlyingAST?, parameters: MutableList<LocalVariableNode>?): MungoStore {
    a.creatingInitialStore = true
    val ret = super.initialStore(underlyingAST, parameters)
    a.creatingInitialStore = false
    return ret
  }

  private fun unwrap(node: Node): Pair<TreePath, TreePath>? {
    var path = a.typeFactory.getPath(node.tree) ?: return null
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

  private fun setValue(result: TransferResult<MungoValue, MungoStore>, receiver: FlowExpressions.Receiver, value: MungoValue?): Boolean {
    if (value == null) {
      return false
    }
    return if (result.containsTwoStores()) {
      val thenChanged = result.thenStore.replaceValueIfDiff(receiver, value)
      val elseChanged = result.elseStore.replaceValueIfDiff(receiver, value)
      thenChanged || elseChanged
    } else {
      result.regularStore.replaceValueIfDiff(receiver, value)
    }
  }

  private fun newResult(result: TransferResult<MungoValue, MungoStore>): TransferResult<MungoValue, MungoStore> {
    return if (result.containsTwoStores()) {
      ConditionalTransferResult(result.resultValue, result.thenStore, result.elseStore, true)
    } else {
      RegularTransferResult(result.resultValue, result.regularStore, true)
    }
  }

}
