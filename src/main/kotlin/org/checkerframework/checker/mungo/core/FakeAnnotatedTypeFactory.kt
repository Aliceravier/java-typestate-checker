package org.checkerframework.checker.mungo.core

import com.sun.source.tree.Tree
import org.checkerframework.checker.mungo.qualifiers.MungoBottom
import org.checkerframework.checker.mungo.qualifiers.MungoInternalInfo
import org.checkerframework.checker.mungo.qualifiers.MungoUnknown
import org.checkerframework.checker.mungo.typecheck.MungoBottomType
import org.checkerframework.checker.mungo.typecheck.MungoUnknownType
import org.checkerframework.common.basetype.BaseTypeChecker
import org.checkerframework.framework.source.SourceChecker
import org.checkerframework.framework.stub.StubTypes
import org.checkerframework.framework.type.AnnotatedTypeFactory
import org.checkerframework.framework.type.AnnotatedTypeMirror
import org.checkerframework.framework.type.QualifierHierarchy
import org.checkerframework.framework.util.GraphQualifierHierarchy
import org.checkerframework.framework.util.MultiGraphQualifierHierarchy
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element

private class FakeBasicTypeChecker(myChecker: SourceChecker) : BaseTypeChecker() {
  init {
    processingEnvironment = myChecker.processingEnvironment
  }
}

class FakeAnnotatedTypeFactory(myChecker: SourceChecker) : AnnotatedTypeFactory(FakeBasicTypeChecker(myChecker)) {

  private val typesFromStubFilesField = StubTypes::class.java.getDeclaredField("typesFromStubFiles")
  private val typesFromStubFiles = mutableMapOf<Element, AnnotatedTypeMirror>()

  private val topAnnotation = MungoUnknownType.SINGLETON.buildAnnotation(checker.processingEnvironment)
  private val bottomAnnotation = MungoBottomType.SINGLETON.buildAnnotation(checker.processingEnvironment)

  init {
    typesFromStubFilesField.isAccessible = true
    qualHierarchy = createQualifierHierarchy()
    typeHierarchy = createTypeHierarchy()
    typeVarSubstitutor = createTypeVariableSubstitutor()
    typeArgumentInference = createTypeArgumentInference()
    qualifierUpperBounds = createQualifierUpperBounds()
    parseStubFiles()
  }

  override fun createSupportedTypeQualifiers(): Set<Class<out Annotation>> {
    return setOf(MungoBottom::class.java, MungoInternalInfo::class.java, MungoUnknown::class.java)
  }

  override fun createQualifierHierarchy(factory: MultiGraphQualifierHierarchy.MultiGraphFactory): QualifierHierarchy {
    return MungoQualifierHierarchy(factory, bottomAnnotation)
  }

  private inner class MungoQualifierHierarchy(f: MultiGraphFactory, bottom: AnnotationMirror) : GraphQualifierHierarchy(f, bottom) {
    override fun findTops(supertypes: MutableMap<AnnotationMirror, MutableSet<AnnotationMirror>>?): MutableSet<AnnotationMirror> {
      return mutableSetOf(topAnnotation)
    }

    override fun findBottoms(supertypes: MutableMap<AnnotationMirror, MutableSet<AnnotationMirror>>?): MutableSet<AnnotationMirror> {
      return mutableSetOf(bottomAnnotation)
    }

    override fun getTopAnnotation(start: AnnotationMirror?): AnnotationMirror = topAnnotation

    override fun getBottomAnnotation(start: AnnotationMirror?): AnnotationMirror = bottomAnnotation

    override fun isSubtype(subAnno: AnnotationMirror, superAnno: AnnotationMirror): Boolean {
      return true
    }
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

  override fun shouldWarnIfStubRedundantWithBytecode(): Boolean {
    return true
  }

  private lateinit var analyzer: Analyzer

  fun setAnalyzer(analyzer: Analyzer) {
    this.analyzer = analyzer
  }

  override fun getAnnotatedType(tree: Tree): AnnotatedTypeMirror {
    val type = super.getAnnotatedType(tree)
    val info = analyzer.getInferredInfoOptional(tree)
    if (info != null) {
      if (info.type is AnnotatedTypeMirror.AnnotatedDeclaredType) {
        return info.type
      }
    }
    return type
  }

}
