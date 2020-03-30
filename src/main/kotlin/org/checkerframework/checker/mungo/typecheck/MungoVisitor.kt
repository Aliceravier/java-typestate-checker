package org.checkerframework.checker.mungo.typecheck

import com.sun.source.tree.ExpressionTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.Tree
import com.sun.tools.javac.code.Symbol
import org.checkerframework.checker.mungo.MungoChecker
import org.checkerframework.checker.mungo.analysis.MungoValue
import org.checkerframework.checker.mungo.annotators.MungoAnnotatedTypeFactory
import org.checkerframework.common.basetype.BaseTypeVisitor
import org.checkerframework.dataflow.cfg.node.LocalVariableNode
import org.checkerframework.javacutil.TreeUtils
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
        MungoTypecheck.check(c.utils, visitorState.path, receiverValue, node, element)
      }
      // TODO deal with self receiver
    }
    return super.visitMethodInvocation(node, p)
  }

  override fun commonAssignmentCheck(left: Tree, right: ExpressionTree, errorKey: String?) {
    val leftValue: MungoValue? = typeFactory.getStoreBefore(left)?.getValue(LocalVariableNode(left))
    val rightValue: MungoValue? = typeFactory.getInferredValueFor(right)

    if (leftValue == null || rightValue == null) {
      return
    }

    // Only allow null assignments on...
    if (MungoNullType.SINGLETON.isSubtype(rightValue.info)) {
      // ... null, ended, moved object, or object without protocol
      val acceptedTypes = listOf(MungoNullType.SINGLETON, MungoMovedType.SINGLETON, MungoEndedType.SINGLETON, MungoNoProtocolType.SINGLETON)
      if (!leftValue.info.isSubtype(MungoUnionType.create(acceptedTypes))) {
        c.utils.err("Cannot assign null because object has not ended its protocol", left)
      }
    }
  }
}
