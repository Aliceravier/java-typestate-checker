package org.checkerframework.checker.mungo.core

import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.common.basetype.BaseTypeChecker
import org.checkerframework.framework.source.SourceChecker
import org.checkerframework.framework.stub.StubTypes
import org.checkerframework.framework.type.AnnotatedTypeFactory
import org.checkerframework.framework.type.AnnotatedTypeMirror
import org.checkerframework.framework.util.CFContext
import org.checkerframework.javacutil.BugInCF
import javax.annotation.processing.ProcessingEnvironment
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element

private class FakeBasicTypeChecker(private val myChecker: SourceChecker) : BaseTypeChecker() {
  override fun getProcessingEnvironment(): ProcessingEnvironment {
    return myChecker.processingEnvironment
  }
}

private class FakeAnnotatedTypeFactory(private val myChecker: SourceChecker) : AnnotatedTypeFactory(FakeBasicTypeChecker(myChecker)) {

  private val typesFromStubFilesField = StubTypes::class.java.getDeclaredField("typesFromStubFiles")
  private val typesFromStubFiles = mutableMapOf<Element, AnnotatedTypeMirror>()

  init {
    typesFromStubFilesField.isAccessible = true
    qualHierarchy = createQualifierHierarchy()
    typeHierarchy = createTypeHierarchy()
    typeVarSubstitutor = createTypeVariableSubstitutor()
    typeArgumentInference = createTypeArgumentInference()
    qualifierUpperBounds = createQualifierUpperBounds()
    parseStubFiles()
  }

  override fun parseStubFiles() {
    super.parseStubFiles()

    val types = typesFromStubFilesField.get(stubTypes) as MutableMap<*, *>
    for ((element, annotatedType) in types) {
      typesFromStubFiles[element as Element] = annotatedType as AnnotatedTypeMirror
    }
  }

  // So that we get the original AnnotatedTypeMirror, with all the annotations
  // See "isSupportedQualifier" for context
  fun getTypeFromStub(elt: Element) = typesFromStubFiles[elt]

  // Allow all annotations to be added to AnnotatedTypeMirror's
  override fun isSupportedQualifier(a: AnnotationMirror?) = a != null
  override fun isSupportedQualifier(className: String?) = className != null
  override fun isSupportedQualifier(clazz: Class<out Annotation>?) = clazz != null

  override fun getContext(): CFContext {
    return myChecker
  }

  override fun getProcessingEnv(): ProcessingEnvironment {
    return myChecker.processingEnvironment
  }

  override fun shouldWarnIfStubRedundantWithBytecode(): Boolean {
    return true
  }

  override fun fromElement(elt: Element): AnnotatedTypeMirror {
    return super.fromElement(elt)
  }

}

class StubFilesProcessor(private val checker: SourceChecker) {
  private lateinit var fakeFactory: FakeAnnotatedTypeFactory

  fun init() {
    fakeFactory = FakeAnnotatedTypeFactory(checker)
  }

  fun getTypeFromStub(elt: Element) = fakeFactory.getTypeFromStub(elt)
}
