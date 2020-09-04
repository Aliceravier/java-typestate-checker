package org.checkerframework.checker.mungo.core

import com.sun.source.tree.*
import com.sun.tools.javac.code.Type
import org.checkerframework.javacutil.TreeUtils
import java.util.*
import javax.lang.model.element.ExecutableElement
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.type.TypeVariable
import javax.lang.model.type.WildcardType

fun treeToType(tree: Tree) = when (tree) {
  is ClassTree -> TreeUtils.elementFromDeclaration(tree).asType()
  is MethodTree -> TreeUtils.elementFromDeclaration(tree).asType()
  is VariableTree -> TreeUtils.elementFromDeclaration(tree).asType()
  is ExpressionTree -> TreeUtils.typeOf(TreeUtils.withoutParens(tree))
  else -> throw RuntimeException("unknown kind ${tree.kind}")
}

// Adapted from TreeUtils.isSelfAccess
fun isSelfAccess(tree: ExpressionTree): Boolean {
  var tr = TreeUtils.withoutParens(tree)
  if (tr.kind == Tree.Kind.ARRAY_ACCESS) {
    return false
  }

  if (tree is MethodInvocationTree) {
    tr = tree.methodSelect
  }
  tr = TreeUtils.withoutParens(tr)

  if (tr is TypeCastTree) {
    tr = tr.expression
  }
  tr = TreeUtils.withoutParens(tr)

  if (tr.kind == Tree.Kind.IDENTIFIER) {
    return true
  }

  if (tr is MemberSelectTree) {
    tr = tr.expression
    // Fix missing
    return TreeUtils.isExplicitThisDereference(tr)
  }

  return false
}

// Adapted from AnnotatedTypes.getArrayDepth
fun getArrayDepth(array: TypeMirror): Int {
  var counter = 0
  var type = array
  while (type.kind == TypeKind.ARRAY) {
    counter++
    type = (type as Type.ArrayType).componentType
  }
  return counter
}

fun isLastArgumentArrayMatchingVararg(varargs: Type.ArrayType, parameters: List<TypeMirror>, args: List<ExpressionTree>): Boolean {
  if (parameters.size == args.size) {
    // Check if one sent an element or an array
    val lastArg = treeToType(args.last())
    if (
      lastArg.kind == TypeKind.ARRAY &&
      getArrayDepth(varargs) == getArrayDepth(lastArg)
    ) {
      return true
    }
  }
  return false
}

// Adapted from AnnotatedTypes.expandVarArgs
fun expandVarArgs(method: ExecutableElement, parameters: List<TypeMirror>, args: List<ExpressionTree>): List<TypeMirror> {
  if (!method.isVarArgs) {
    return parameters
  }
  val varargs = parameters.last() as Type.ArrayType
  if (isLastArgumentArrayMatchingVararg(varargs, parameters, args)) {
    return parameters
  }
  val newParameters = ArrayList(parameters.subList(0, parameters.size - 1))
  for (i in args.size - parameters.size downTo 1) {
    newParameters.add(varargs.componentType)
  }
  return newParameters
}

fun upperBound(type: TypeMirror): TypeMirror {
  var type = type
  loop@ do {
    type = when (type) {
      is TypeVariable -> if (type.upperBound != null) type.upperBound else break@loop
      is WildcardType -> if (type.extendsBound != null) type.extendsBound else break@loop
      else -> break@loop
    }
  } while (true)
  return type
}

fun lowerBound(type: TypeMirror): TypeMirror {
  var type = type
  loop@ do {
    type = when (type) {
      is TypeVariable -> if (type.lowerBound != null) type.lowerBound else break@loop
      is WildcardType -> if (type.superBound != null) type.superBound else break@loop
      else -> break@loop
    }
  } while (true)
  return type
}
