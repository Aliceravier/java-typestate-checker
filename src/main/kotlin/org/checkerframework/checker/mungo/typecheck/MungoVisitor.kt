package org.checkerframework.checker.mungo.typecheck

import com.sun.source.tree.ExpressionTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.tools.javac.code.Symbol
import org.checkerframework.checker.mungo.MungoChecker
import org.checkerframework.checker.mungo.analysis.MungoStore
import org.checkerframework.checker.mungo.analysis.MungoValue
import org.checkerframework.checker.mungo.annotators.MungoAnnotatedTypeFactory
import org.checkerframework.common.basetype.BaseTypeVisitor
import org.checkerframework.dataflow.cfg.node.LocalVariableNode
import org.checkerframework.framework.type.AnnotatedTypeMirror
import org.checkerframework.javacutil.TreeUtils
import org.checkerframework.org.plumelib.util.WeakIdentityHashMap
import javax.lang.model.element.ElementKind

class MungoVisitor(checker: MungoChecker) : BaseTypeVisitor<MungoAnnotatedTypeFactory>(checker) {

  private val c = checker

  override fun createTypeFactory(): MungoAnnotatedTypeFactory {
    // Pass "checker" and not "c" because "c" is initialized after "super()" and "createTypeFactory()"...
    return MungoAnnotatedTypeFactory(checker as MungoChecker)
  }

  // TODO visit all annotations to make sure @MungoTypestate only appears in class/interfaces??
  // TODO what if another class points to the same protocol file?? error? or fine? avoid duplicate processing

  override fun visitMethodInvocation(node: MethodInvocationTree, p: Void?): Void? {
    val element = TreeUtils.elementFromUse(node)
    if (element is Symbol.MethodSymbol && element.getKind() == ElementKind.METHOD) {
      val receiver = TreeUtils.getReceiverTree(node)
      if (receiver != null) {
        val receiverValue = typeFactory.getInferredValueFor(receiver)
        val checks = MungoTypecheck.check(c.utils, visitorState.path, receiverValue, node, element)
        if (!checks) {
          // Ignore this method invocation tree to avoid (method.invocation.invalid) errors produced by Checker
          skipMethods[node] = true
        }
      }
      // TODO deal with self receiver
    }

    return super.visitMethodInvocation(node, p)
  }

  private val skipMethods = WeakIdentityHashMap<MethodInvocationTree, Boolean>()

  override fun skipReceiverSubtypeCheck(node: MethodInvocationTree, methodDefinitionReceiver: AnnotatedTypeMirror, methodCallReceiver: AnnotatedTypeMirror): Boolean {
    return skipMethods.contains(node) || super.skipReceiverSubtypeCheck(node, methodDefinitionReceiver, methodCallReceiver)
  }

  private val acceptedFinalTypes = listOf(MungoNullType.SINGLETON, MungoMovedType.SINGLETON, MungoEndedType.SINGLETON, MungoNoProtocolType.SINGLETON)

  override fun commonAssignmentCheck(left: Tree, right: ExpressionTree, errorKey: String?) {
    val leftValue: MungoValue? = typeFactory.getStoreBefore(left)?.getValue(LocalVariableNode(left))
    val rightValue: MungoValue? = typeFactory.getInferredValueFor(right)

    if (leftValue == null || rightValue == null) {
      return
    }

    // Only allow overrides on null, ended, moved object, or object without protocol
    if (!leftValue.info.isSubtype(MungoUnionType.create(acceptedFinalTypes))) {
      c.utils.err("Cannot override because object has not ended its protocol", left)
    }
  }

  private fun ensureCompleteness(exitStore: MungoStore) {
    // Make sure protocols complete
    for ((key, value) in exitStore.iterateOverLocalVars()) {
      if (!value.info.isSubtype(MungoUnionType.create(acceptedFinalTypes))) {
        c.utils.err("Object did not complete its protocol", key.element)
      }
    }
  }

  override fun visitMethod(node: MethodTree, p: Void?): Void? {
    typeFactory.getRegularExitStore(node)?.let { ensureCompleteness(it) }
    return super.visitMethod(node, p)
  }

  // TODO what to do in the presence of returns? how to ensure the protocol ends?
  // TODO ensure for blocks as well

  /*override fun visitBlock(node: BlockTree, p: Void?): Void? {
    println("block")
    println(node)
    typeFactory.getRegularExitStore(node)?.let { println(it) }
    println()
    return super.visitBlock(node, p)
  }*/
}
