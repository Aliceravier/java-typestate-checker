package org.checkerframework.checker.mungo

import org.checkerframework.checker.mungo.typecheck.MungoVisitor
import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.common.basetype.BaseTypeChecker
import org.checkerframework.common.basetype.BaseTypeVisitor
import org.checkerframework.javacutil.trees.TreeBuilder

/**
 * This is the entry point for pluggable type-checking.
 */
class MungoChecker : BaseTypeChecker() {

  private lateinit var _utils: MungoUtils
  val utils: MungoUtils
    get() {
      if (!this::_utils.isInitialized) {
        _utils = MungoUtils(this)
      }
      return _utils
    }

  override fun createSourceVisitor(): BaseTypeVisitor<*> {
    return MungoVisitor(this)
  }
}