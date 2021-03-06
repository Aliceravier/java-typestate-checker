package org.checkerframework.checker.mungo.typecheck

import com.sun.source.tree.*
import com.sun.tools.javac.code.Symbol
import org.checkerframework.checker.mungo.MungoChecker
import org.checkerframework.checker.mungo.utils.isSelfAccess
import org.checkerframework.checker.mungo.utils.lowerBound
import org.checkerframework.checker.mungo.utils.upperBound
import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.framework.type.AnnotatedTypeMirror
import org.checkerframework.framework.util.AnnotatedTypes
import org.checkerframework.javacutil.AnnotationUtils
import org.checkerframework.javacutil.ElementUtils
import org.checkerframework.javacutil.TreeUtils
import java.util.*
import javax.lang.model.element.ElementKind
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror

// Nullness checks adapted from https://github.com/typetools/checker-framework/blob/master/checker/src/main/java/org/checkerframework/checker/nullness/NullnessVisitor.java
// TODO check redundant tests
// TODO check arrays of non-null items are initialized

class Typechecker(checker: MungoChecker) : TypecheckerHelpers(checker) {

  // root
  // currentPath

  override fun visitAnnotation(node: AnnotationTree, p: Void?): Void? {
    val annoMirror = TreeUtils.annotationFromAnnotationTree(node)
    val parent = currentPath.parentPath.parentPath.leaf
    val parentParent = currentPath.parentPath.parentPath.parentPath.leaf

    when (AnnotationUtils.annotationName(annoMirror)) {
      MungoUtils.mungoState -> checkReturnTypeAnnotation(node, annoMirror, parent)
      MungoUtils.mungoRequires -> checkParameterAnnotation(node, annoMirror, parent, parentParent, "MungoRequires")
      MungoUtils.mungoEnsures -> checkParameterAnnotation(node, annoMirror, parent, parentParent, "MungoEnsures")
    }

    // TODO make sure @MungoTypestate only appears in classes

    return null
  }

  override fun visitCompilationUnit(node: CompilationUnitTree, p: Void?): Void? {
    var r = scan(node.packageAnnotations, p)
    // r = reduce(scan(node.getPackageName(), p), r);
    // r = reduce(scan(node.getImports(), p), r);
    r = reduce(scan(node.typeDecls, p), r)
    return r
  }

  override fun visitClass(classTree: ClassTree, p: Void?): Void? {
    utils.factory.setRoot(root)
    analyzer.setRoot(root)
    analyzer.run(classTree)

    analyzer.getStatesToStore(classTree)?.let {
      for ((state, store) in it) {
        if (state.canEndHere()) {
          ensureFieldsCompleteness(store)
        }
      }
    }

    analyzer.getGlobalStore(classTree)?.let {
      ensureFieldsCompleteness(it)
    }

    // TODO checkExtendsImplements: If "@B class Y extends @A X {}", then enforce that @B must be a subtype of @A.

    return super.visitClass(classTree, p)
  }

  override fun visitMethod(node: MethodTree, p: Void?): Void? {
    analyzer.getRegularExitStore(node)?.let { ensureLocalCompleteness(node.parameters, it) }

    // TODO check that the method obeys override and subtype rules to all overridden methods
    // TODO AnnotatedTypes.overriddenMethods(elements, atypeFactory, methodElement)
    // The override rule specifies that a method, m1, may override a method m2 only if:
    // m1 return type is a subtype of m2;
    // m1 receiver type is a supertype of m2;
    // m1 parameters are supertypes of corresponding m2 parameters

    return super.visitMethod(node, p)
  }

  override fun visitVariable(node: VariableTree, p: Void?): Void? {
    if (node.initializer != null) {
      commonAssignmentCheck(node, node.initializer, "assignment.type.incompatible")
    }
    return super.visitVariable(node, p)
  }

  override fun visitAssignment(node: AssignmentTree, p: Void?): Void? {
    commonAssignmentCheck(node.variable, node.expression, "assignment.type.incompatible")
    return super.visitAssignment(node, p)
  }

  override fun visitEnhancedForLoop(node: EnhancedForLoopTree, p: Void?): Void? {
    checkForNullability(node.expression, ITERATING_NULLABLE)

    // Performs a subtype check, to test whether the node expression iterable type is a subtype of the variable type in the enhanced for loop
    val variable = utils.factory.getAnnotatedType(node.variable)
    val iterableType = utils.factory.getAnnotatedType(node.expression)
    val iteratedType = utils.factory.getIteratedType(iterableType)
    commonAssignmentCheck(
      variable,
      node.variable,
      iteratedType,
      node.expression,
      "enhancedfor.type.incompatible"
    )

    return super.visitEnhancedForLoop(node, p)
  }

  override fun visitMethodInvocation(node: MethodInvocationTree, p: Void?): Void? {
    val element = TreeUtils.elementFromUse(node)

    // Skip calls to the Enum constructor (they're generated by javac and
    // hard to check), also see CFGBuilder.visitMethodInvocation.
    if (element == null || TreeUtils.isEnumSuper(node)) {
      return super.visitMethodInvocation(node, p)
    }

    if (element is Symbol.MethodSymbol && element.getKind() == ElementKind.METHOD) {
      if (checkOwnCall(node, element)) {
        val receiverTree = TreeUtils.getReceiverTree(node)
        if (receiverTree != null) {
          val type = analyzer.getInferredType(receiverTree)
          MungoTypecheck.check(utils, type, node, element)
        }
      }

      // Returned objects must be assigned so that they complete the protocol
      val parent = currentPath.parentPath.leaf
      if (parent !is VariableTree && parent !is AssignmentTree) {
        val type = analyzer.getInferredType(node)
        if (!MungoTypecheck.canDrop(type)) {
          utils.err("Returned object did not complete its protocol. Type: ${type.format()}", node)
        }
      }
    }

    if (ElementUtils.isElementFromByteCode(element)) {
      for (arg in node.arguments) {
        val type = analyzer.getInferredType(arg)
        if (!type.isSubtype(MungoTypecheck.noProtocolTypes)) {
          utils.err("Passing an object with protocol to a method that cannot be analyzed", arg)
        }
      }
    }

    // TODO checks
    // An invocation of a method, m, on the receiver, r is valid only if:
    // passed arguments are subtypes of corresponding m parameters;
    // r is a subtype of m receiver type;
    // if m is generic, passed type arguments are subtypes of m type variables

    val mType = utils.factory.methodFromUse(node)
    val invokedMethod = mType.executableType
    // val typeargs = mType.typeArgs

    val expectedParams = utils.factory.expandVarArgs(invokedMethod, node)

    for (i in expectedParams.indices) {
      commonAssignmentCheckParameter(expectedParams[i], node.arguments[i], "argument.type.incompatible")
    }

    val upperBounds = mutableListOf<TypeMirror>()
    val lowerBounds = mutableListOf<TypeMirror>()
    for (param in element.typeParameters) {
      upperBounds.add(upperBound(param.asType()))
      lowerBounds.add(lowerBound(param.asType()))
    }

    // TODO checkTypeArguments(node, paramBounds, typeargs, node.typeArguments)
    // TODO why? checkVarargs(element, node)

    return super.visitMethodInvocation(node, p)
  }

  override fun visitNewClass(node: NewClassTree, p: Void?): Void? {
    val element = TreeUtils.constructor(node)

    // TODO new class invocation check
    // An invocation of a constructor, c, is valid only if
    // passed arguments are subtypes of corresponding c parameters
    // if c is generic, passed type arguments are subtypes of c type variables

    val mType = utils.factory.constructorFromUse(node)
    val invokedMethod = mType.executableType
    // val typeargs = mType.typeArgs

    val expectedParams = utils.factory.expandVarArgs(invokedMethod, node)

    for (i in expectedParams.indices) {
      commonAssignmentCheckParameter(expectedParams[i], node.arguments[i], "argument.type.incompatible")
    }

    val upperBounds = mutableListOf<TypeMirror>()
    val lowerBounds = mutableListOf<TypeMirror>()
    for (param in element.typeParameters) {
      upperBounds.add(upperBound(param.asType()))
      lowerBounds.add(lowerBound(param.asType()))
    }

    // TODO checkTypeArguments(node, paramBounds, typeargs, node.typeArguments)
    // TODO why? checkVarargs(element, node)

    return super.visitNewClass(node, p)
  }

  override fun visitLambdaExpression(node: LambdaExpressionTree, p: Void?): Void? {
    analyzer.getRegularExitStore(node.body)?.let { ensureLocalCompleteness(node.parameters, it) }

    val functionType = utils.factory.getFunctionTypeFromTree(node)

    if (node.body.kind != Tree.Kind.BLOCK) {
      // Check return type for single statement returns here.
      val ret = functionType.returnType
      if (ret.kind != TypeKind.VOID) {
        commonAssignmentCheckReturn(ret, node.body)
      }
    }

    // Check parameters
    for (i in functionType.parameterTypes.indices) {
      val lambdaParam = utils.factory.getAnnotatedType(node.parameters[i])
      commonAssignmentCheck(
        lambdaParam,
        null,
        functionType.parameterTypes[i],
        node.parameters[i],
        "lambda.param.type.incompatible"
      )
    }

    return super.visitLambdaExpression(node, p)
  }

  override fun visitMemberReference(node: MemberReferenceTree, p: Void?): Void? {
    val expr = node.qualifierExpression
    val type = analyzer.getInferredType(expr)
    if (!type.isSubtype(MungoTypecheck.noProtocolTypes)) {
      utils.err("Cannot create reference for method of an object with protocol", node)
    }
    return super.visitMemberReference(node, p)
  }

  override fun visitReturn(node: ReturnTree, p: Void?): Void? {
    // Don't try to check return expressions for void methods.
    if (node.expression == null) {
      return super.visitReturn(node, p)
    }
    val enclosing = TreeUtils.enclosingOfKind(currentPath, HashSet(listOf(Tree.Kind.METHOD, Tree.Kind.LAMBDA_EXPRESSION)))!!
    val ret = if (enclosing is MethodTree) {
      utils.factory.getMethodReturnType(enclosing, node)
    } else {
      utils.factory.getFunctionTypeFromTree(enclosing as LambdaExpressionTree).returnType
    }
    if (ret != null) {
      commonAssignmentCheckReturn(ret, node.expression)
    }
    return super.visitReturn(node, p)
  }

  override fun visitUnary(node: UnaryTree, p: Void?): Void? {
    checkForNullability(node.expression, UNBOXING_OF_NULLABLE)

    val nodeKind = node.kind
    if (nodeKind == Tree.Kind.PREFIX_DECREMENT
      || nodeKind == Tree.Kind.PREFIX_INCREMENT
      || nodeKind == Tree.Kind.POSTFIX_DECREMENT
      || nodeKind == Tree.Kind.POSTFIX_INCREMENT) {
      val varType = utils.factory.getAnnotatedType(node.expression)
      val valueType = utils.factory.getAnnotatedType(node)
      val errorKey = if (nodeKind == Tree.Kind.PREFIX_INCREMENT || nodeKind == Tree.Kind.POSTFIX_INCREMENT)
        "unary.increment.type.incompatible" else
        "unary.decrement.type.incompatible"
      commonAssignmentCheck(varType, null, valueType, node, errorKey)
    }
    return super.visitUnary(node, p)
  }

  override fun visitCompoundAssignment(node: CompoundAssignmentTree, p: Void?): Void? {
    // ignore String concatenation
    if (!isString(node)) {
      checkForNullability(node.variable, UNBOXING_OF_NULLABLE)
      checkForNullability(node.expression, UNBOXING_OF_NULLABLE)
    }

    // If node is the tree representing the compounds assignment s += expr,
    // Then this method should check whether s + expr can be assigned to s,
    // but the "s + expr" tree does not exist.  So instead, check that
    // s += expr can be assigned to s.
    commonAssignmentCheck(node.variable, node, "compound.assignment.type.incompatible")
    return super.visitCompoundAssignment(node, p)
  }

  override fun visitNewArray(node: NewArrayTree, p: Void?): Void? {
    val arrayType = utils.factory.getAnnotatedType(node) as AnnotatedTypeMirror.AnnotatedArrayType
    if (node.initializers != null) {
      for (init in node.initializers) {
        commonAssignmentCheckParameter(arrayType.componentType, init, "array.initializer.type.incompatible")
      }
    }
    return super.visitNewArray(node, p)
  }

  private fun checkTypecastSafety(typeCastTree: TypeCastTree) {
    val castType = analyzer.getInferredType(typeCastTree)
    val exprType = analyzer.getInferredType(typeCastTree.expression)
    if (!exprType.isSubtype(castType)) {
      checker.reportWarning(typeCastTree, "cast.unsafe", exprType.format(), castType.format())
    }
  }

  override fun visitTypeCast(node: TypeCastTree, p: Void?): Void? {
    if (isPrimitive(node) && !isPrimitive(node.expression)) {
      if (!checkForNullability(node.expression, UNBOXING_OF_NULLABLE)) {
        // If unboxing of nullable is issued, don't issue any other errors.
        return null
      }
    }
    checkTypecastSafety(node)
    // TODO checkTypecastRedundancy(node)
    return super.visitTypeCast(node, p)
  }

  override fun visitThrow(node: ThrowTree, p: Void?): Void? {
    checkForNullability(node.expression, THROWING_NULLABLE)
    return super.visitThrow(node, p)
  }

  override fun visitIdentifier(node: IdentifierTree, p: Void?): Void? {
    val element = TreeUtils.elementFromTree(node) as? Symbol.VarSymbol ?: return p

    // Print type information for testing purposes
    printTypeInfo(currentPath, node)

    if (utils.wasMovedToDiffClosure(currentPath, node, element)) {
      utils.err("$node was moved to a different closure", node)
    }

    return super.visitIdentifier(node, p)
  }

  override fun visitMemberSelect(node: MemberSelectTree, p: Void?): Void? {
    super.visitMemberSelect(node, p)

    val element = TreeUtils.elementFromTree(node) ?: return p

    // Check this field access if this is not a self access, or static access, or method call
    if (!(TreeUtils.isExplicitThisDereference(node) || isSelfAccess(node) || ElementUtils.isStatic(element))) {
      val parent = currentPath.parentPath.leaf
      if (!(parent is MethodInvocationTree && parent.methodSelect === node)) {
        val typeInfo = analyzer.getInferredType(node.expression)
        MungoTypecheck.checkFieldAccess(utils, typeInfo, node)
      }
    }

    // See if it has protocol
    utils.classUtils.visitClassOfElement(element) ?: return p

    // If "this"...
    if (TreeUtils.isExplicitThisDereference(node)) {
      val enclosingMethodOrLambda = MungoUtils.enclosingMethodOrLambda(currentPath) ?: return p
      if (enclosingMethodOrLambda.leaf.kind == Tree.Kind.LAMBDA_EXPRESSION) {
        utils.err("$node was moved to a different closure", node)
      }
    }

    return p
  }

  override fun visitArrayAccess(node: ArrayAccessTree, p: Void?): Void? {
    checkForNullability(node.expression, ACCESSING_NULLABLE)
    return super.visitArrayAccess(node, p)
  }

  override fun visitSynchronized(node: SynchronizedTree, p: Void?): Void? {
    checkForNullability(node.expression, LOCKING_NULLABLE)
    return super.visitSynchronized(node, p)
  }

  override fun visitIf(node: IfTree, p: Void?): Void? {
    checkForNullability(node.condition, CONDITION_NULLABLE)
    return super.visitIf(node, p)
  }

  override fun visitBinary(node: BinaryTree, p: Void?): Void? {
    val leftOp = node.leftOperand
    val rightOp = node.rightOperand
    if (isUnboxingOperation(node)) {
      checkForNullability(leftOp, UNBOXING_OF_NULLABLE)
      checkForNullability(rightOp, UNBOXING_OF_NULLABLE)
    }
    return super.visitBinary(node, p)
  }

  override fun visitSwitch(node: SwitchTree, p: Void?): Void? {
    checkForNullability(node.expression, SWITCHING_NULLABLE)
    return super.visitSwitch(node, p)
  }

  override fun visitForLoop(node: ForLoopTree, p: Void?): Void? {
    if (node.condition != null) {
      // Condition is null e.g. in "for (;;) {...}"
      checkForNullability(node.condition, CONDITION_NULLABLE)
    }
    return super.visitForLoop(node, p)
  }

  override fun visitWhileLoop(node: WhileLoopTree, p: Void?): Void? {
    checkForNullability(node.condition, CONDITION_NULLABLE)
    return super.visitWhileLoop(node, p)
  }

  override fun visitDoWhileLoop(node: DoWhileLoopTree, p: Void?): Void? {
    checkForNullability(node.condition, CONDITION_NULLABLE)
    return super.visitDoWhileLoop(node, p)
  }

  override fun visitConditionalExpression(node: ConditionalExpressionTree, p: Void?): Void? {
    checkForNullability(node.condition, CONDITION_NULLABLE)
    return super.visitConditionalExpression(node, p)
  }

}
