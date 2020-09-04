package org.checkerframework.checker.mungo.utils

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.Tree
import com.sun.source.util.TreePath
import com.sun.tools.javac.code.Symbol
import com.sun.tools.javac.code.Symtab
import com.sun.tools.javac.code.Type
import com.sun.tools.javac.code.TypeTag
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.processing.JavacProcessingEnvironment
import com.sun.tools.javac.util.JCDiagnostic
import com.sun.tools.javac.util.Log
import org.checkerframework.checker.mungo.core.FakeAnnotatedTypeFactory
import org.checkerframework.checker.mungo.lib.*
import org.checkerframework.checker.mungo.qualifiers.MungoBottom
import org.checkerframework.checker.mungo.qualifiers.MungoInternalInfo
import org.checkerframework.checker.mungo.qualifiers.MungoUnknown
import org.checkerframework.checker.mungo.typecheck.MungoBottomType
import org.checkerframework.checker.mungo.typecheck.MungoType
import org.checkerframework.checker.mungo.typecheck.MungoUnknownType
import org.checkerframework.checker.mungo.typecheck.getTypeFromAnnotation
import org.checkerframework.checker.mungo.typestate.TypestateProcessor
import org.checkerframework.checker.mungo.typestate.graph.Graph
import org.checkerframework.framework.source.SourceChecker
import org.checkerframework.framework.stub.StubTypes
import org.checkerframework.framework.type.AnnotatedTypeFactory
import org.checkerframework.javacutil.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.type.TypeMirror
import javax.tools.JavaFileManager

class MungoUtils(val checker: SourceChecker) {

  class LazyField<T>(private val fn: () -> T) {
    private var value: T? = null
    fun get() = value ?: run {
      val v = fn()
      value = v
      v
    }
  }

  val env = (checker.processingEnvironment as JavacProcessingEnvironment)
  val ctx = env.context
  val symtab = Symtab.instance(ctx)
  val log = Log.instance(ctx)
  val fileManager = ctx.get(JavaFileManager::class.java) as JavacFileManager

  val treeUtils = checker.treeUtils
  val typeUtils = checker.typeUtils

  val resolver = Resolver(checker)
  val classUtils = ClassUtils(this)
  val configUtils = ConfigUtils(checker)

  val processor = TypestateProcessor(this)
  val methodUtils = MethodUtils(this)

  lateinit var factory: FakeAnnotatedTypeFactory

  fun initFactory() {
    factory = FakeAnnotatedTypeFactory(checker)
  }

  fun getTypeFromStub(elt: Element) = factory.getTypeFromStub(elt)

  // Adapted from SourceChecker#report and JavacTrees#printMessage
  private fun reportError(file: Path, pos: Int, messageKey: String, vararg args: Any?) {
    val defaultFormat = "($messageKey)"
    val fmtString = if (env.options != null && env.options.containsKey("nomsgtext")) {
      defaultFormat
    } else if (env.options != null && env.options.containsKey("detailedmsgtext")) {
      // TODO detailedMsgTextPrefix(source, defaultFormat, args) + fullMessageOf(messageKey, defaultFormat)
      defaultFormat
    } else {
      // TODO "[" + suppressionKey(messageKey) + "] " + fullMessageOf(messageKey, defaultFormat)
      defaultFormat
    }
    val messageText = try {
      String.format(fmtString, *args)
    } catch (e: Exception) {
      throw BugInCF("Invalid format string: \"$fmtString\" args: " + args.contentToString(), e)
    }

    val newSource = fileManager.getJavaFileObject(getUserPath(file))
    val oldSource = log.useSource(newSource)
    try {
      log.error(JCDiagnostic.DiagnosticFlag.MULTIPLE, JCDiagnostic.SimpleDiagnosticPosition(pos), "proc.messager", messageText)
    } finally {
      log.useSource(oldSource)
    }
  }

  fun err(message: String, where: Tree) {
    checker.reportError(where, message)
  }

  fun err(message: String, where: Element) {
    checker.reportError(where, message)
  }

  fun err(message: String, file: Path, pos: Int) {
    reportError(file, pos, message)
  }

  fun checkStates(graph: Graph, states: List<String>?): List<String> {
    if (states == null || states.isEmpty()) {
      return listOf("@MungoRequires should specify one or more states")
    }
    val basename = graph.resolvedFile.fileName
    return states.mapNotNull {
      if (graph.isFinalState(it)) {
        "State $it is final. Will have no effect in @MungoRequires"
      } else if (!graph.hasStateByName(it)) {
        "$basename has no $it state"
      } else null
    }
  }

  fun leastUpperBound(a: TypeMirror, b: TypeMirror): TypeMirror {
    // Avoid assertion error in com.sun.tools.javac.code.Types$SameTypeVisitor.visitType(Types.java:1064)
    if ((a as Type).tag == TypeTag.UNKNOWN) return a
    if ((b as Type).tag == TypeTag.UNKNOWN) return b
    return TypesUtils.leastUpperBound(a, b, env)
  }

  fun isSameType(a: TypeMirror?, b: TypeMirror?): Boolean {
    return isSameType(a as Type?, b as Type?)
  }

  fun isSameType(a: Type?, b: Type?): Boolean {
    if (a == null) return b == null
    if (b == null) return false
    if (a.tag == TypeTag.UNKNOWN) return b.tag == TypeTag.UNKNOWN
    if (b.tag == TypeTag.UNKNOWN) return a.tag == TypeTag.UNKNOWN
    // isSameType for methods does not take thrown exceptions into account
    return typeUtils.isSameType(a, b) && isSameTypes(a.thrownTypes, b.thrownTypes)
  }

  fun isSameTypes(aList: List<Type?>, bList: List<Type?>): Boolean {
    if (aList.size != bList.size) {
      return false
    }
    val bIt = bList.iterator()
    for (a in aList) {
      val b = bIt.next()
      if (!isSameType(a, b)) {
        return false
      }
    }
    return true
  }

  fun getPath(tree: Tree, root: CompilationUnitTree): TreePath? = treeUtils.getPath(root, tree)

  fun wasMovedToDiffClosure(path: TreePath, tree: IdentifierTree, element: Symbol.VarSymbol): Boolean {
    // See if it has protocol
    classUtils.visitClassOfElement(element) ?: return false

    // If "this"...
    if (TreeUtils.isExplicitThisDereference(tree)) {
      val enclosingMethodOrLambda = enclosingMethodOrLambda(path) ?: return false
      return enclosingMethodOrLambda.leaf.kind == Tree.Kind.LAMBDA_EXPRESSION
    }

    // Find declaration and enclosing method/lambda
    val declarationTree = treeUtils.getTree(element) ?: return false
    val declaration = treeUtils.getPath(path.compilationUnit, declarationTree) ?: return false
    val enclosingMethodOrLambda = enclosingMethodOrLambda(path) ?: return false

    // See if variable declaration is enclosed in the enclosing method or lambda or not
    var path1: TreePath? = declaration
    var path2: TreePath? = enclosingMethodOrLambda
    while (path1 != null && path2 != null && path1 != enclosingMethodOrLambda) {
      path1 = path1.parentPath
      path2 = path2.parentPath
    }

    // Was moved if:
    // 1. Declaration is closer to the root (path1 == null && path2 != null)
    // 2. Both are at the same level (path1 == null && path2 == null) and identifier is enclosed in a lambda
    return path1 == null && (path2 != null || enclosingMethodOrLambda.leaf.kind == Tree.Kind.LAMBDA_EXPRESSION)
  }

  fun breakpoint() {
    // For debugging purposes
    // Add class pattern "org.checkerframework.checker.mungo.utils.MungoUtils"
    // and method name "breakpoint"
    // to IntelliJ's breakpoints settings
    println("--------")
  }

  companion object {
    val mungoState: String = MungoState::class.java.canonicalName
    val mungoRequires: String = MungoRequires::class.java.canonicalName
    val mungoEnsures: String = MungoEnsures::class.java.canonicalName
    val typestateAnnotations = setOf(MungoTypestate::class.java.canonicalName, "mungo.lib.Typestate")
    val nullableAnnotations = setOf(
      MungoNullable::class.java.canonicalName,
      // From https://github.com/JetBrains/kotlin/blob/master/core/descriptors.jvm/src/org/jetbrains/kotlin/load/java/JvmAnnotationNames.kt
      "org.jetbrains.annotations.Nullable",
      "androidx.annotation.Nullable",
      "android.support.annotation.Nullable",
      "android.annotation.Nullable",
      "com.android.annotations.Nullable",
      "org.eclipse.jdt.annotation.Nullable",
      "org.checkerframework.checker.nullness.qual.Nullable",
      "javax.annotation.Nullable",
      "javax.annotation.CheckForNull",
      "edu.umd.cs.findbugs.annotations.CheckForNull",
      "edu.umd.cs.findbugs.annotations.Nullable",
      "edu.umd.cs.findbugs.annotations.PossiblyNull",
      "io.reactivex.annotations.Nullable"
    )

    // Internal annotations for type information
    val mungoUnknownName: String = MungoUnknown::class.java.canonicalName
    val mungoInternalInfoName: String = MungoInternalInfo::class.java.canonicalName
    val mungoBottomName: String = MungoBottom::class.java.canonicalName

    fun isMungoLibAnnotation(annotation: AnnotationMirror): Boolean {
      val name = AnnotationUtils.annotationName(annotation)
      return name == mungoState ||
        name == mungoRequires ||
        name == mungoEnsures ||
        typestateAnnotations.contains(name) ||
        nullableAnnotations.contains(name)
    }

    fun isMungoInternalAnnotation(annotation: AnnotationMirror): Boolean {
      return AnnotationUtils.areSameByName(annotation, mungoUnknownName) ||
        AnnotationUtils.areSameByName(annotation, mungoInternalInfoName) ||
        AnnotationUtils.areSameByName(annotation, mungoBottomName)
    }

    fun mungoTypeFromAnnotation(annotation: AnnotationMirror): MungoType {
      if (AnnotationUtils.areSameByName(annotation, mungoUnknownName)) {
        return MungoUnknownType.SINGLETON
      }
      if (AnnotationUtils.areSameByName(annotation, mungoInternalInfoName)) {
        return getTypeFromAnnotation(annotation)
      }
      if (AnnotationUtils.areSameByName(annotation, mungoBottomName)) {
        return MungoBottomType.SINGLETON
      }
      throw AssertionError("mungoTypeFromAnnotation")
    }

    fun mungoTypeFromAnnotations(annotations: Collection<AnnotationMirror>): MungoType {
      for (annotation in annotations) {
        if (AnnotationUtils.areSameByName(annotation, mungoUnknownName)) {
          return MungoUnknownType.SINGLETON
        }
        if (AnnotationUtils.areSameByName(annotation, mungoInternalInfoName)) {
          return getTypeFromAnnotation(annotation)
        }
        if (AnnotationUtils.areSameByName(annotation, mungoBottomName)) {
          return MungoBottomType.SINGLETON
        }
      }
      return MungoUnknownType.SINGLETON
    }

    fun getAnnotationValue(annotationMirror: AnnotationMirror?): List<String>? {
      if (annotationMirror == null) return null
      val value = AnnotationUtils.getElementValueArray(annotationMirror, "value", String::class.java, false)
      if (value.isEmpty()) return null
      return value
    }

    fun isBoolean(type: TypeMirror): Boolean {
      return TypesUtils.isBooleanType(type)
    }

    fun isEnum(type: TypeMirror): Boolean {
      return (type as Type).tsym.isEnum
    }

    // Adapted from TreeUtils.enclosingMethodOrLambda to return a TreePath instead of Tree
    fun enclosingMethodOrLambda(path: TreePath): TreePath? {
      return enclosingOfKind(path, EnumSet.of(Tree.Kind.METHOD, Tree.Kind.LAMBDA_EXPRESSION))
    }

    // Adapted from TreeUtils.enclosingOfKind to return a TreePath instead of Tree
    private fun enclosingOfKind(path: TreePath, kinds: Set<Tree.Kind>): TreePath? {
      var p: TreePath? = path
      while (p != null) {
        val leaf = p.leaf
        if (kinds.contains(leaf.kind)) {
          return p
        }
        p = p.parentPath
      }
      return null
    }

    val cwd = Paths.get("").toAbsolutePath()

    fun getUserPath(p: Path): Path = if (p.isAbsolute) cwd.relativize(p) else p

    fun printStack() {
      try {
        throw RuntimeException("debug")
      } catch (exp: RuntimeException) {
        exp.printStackTrace()
      }
    }

    fun <T> printResult(prefix: String, ret: T): T {
      println("$prefix $ret")
      return ret
    }
  }

}
