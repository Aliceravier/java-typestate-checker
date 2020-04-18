package org.checkerframework.checker.mungo.analysis

import org.checkerframework.checker.mungo.MungoChecker
import org.checkerframework.checker.mungo.annotators.MungoAnnotatedTypeFactory
import org.checkerframework.framework.flow.CFAbstractAnalysis
import org.checkerframework.framework.flow.CFAbstractValue
import org.checkerframework.javacutil.Pair
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.VariableElement
import javax.lang.model.type.TypeMirror

class MungoAnalysis(checker: MungoChecker, factory: MungoAnnotatedTypeFactory, fieldValues: List<Pair<VariableElement, MungoValue>>) : CFAbstractAnalysis<MungoValue, MungoStore, MungoTransfer>(checker, factory, fieldValues) {

  val c = checker
  var inClassAnalysis: Boolean = false
  var creatingInitialStore: Boolean = false

  override fun createEmptyStore(sequentialSemantics: Boolean): MungoStore {
    return MungoStore(this, sequentialSemantics)
  }

  override fun createCopiedStore(s: MungoStore?): MungoStore {
    // Workaround null value in CFAbstractTransfer#initialStore in the case AST is a lambda
    if (s == null) return createEmptyStore(transferFunction?.usesSequentialSemantics() ?: false)
    return MungoStore(s)
  }

  override fun createAbstractValue(annotations: Set<AnnotationMirror>, underlyingType: TypeMirror): MungoValue? {
    return if (!CFAbstractValue.validateSet(annotations, underlyingType, qualifierHierarchy)) {
      null
    } else MungoValue(this, annotations, underlyingType)
  }
}
