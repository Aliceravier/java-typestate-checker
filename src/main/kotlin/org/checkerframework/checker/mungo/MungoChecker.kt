package org.checkerframework.checker.mungo

import org.checkerframework.checker.mungo.typecheck.MungoVisitor
import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.common.basetype.BaseTypeChecker
import org.checkerframework.common.basetype.BaseTypeVisitor

const val showTypeInfoOpt = "showTypeInfo"
const val configFile = "configFile"

/**
 * This is the entry point for pluggable type-checking.
 */
class MungoChecker : BaseTypeChecker() {

  private val _utils = MungoUtils.LazyField { MungoUtils(this) }
  val utils get() = _utils.get()

  override fun createSourceVisitor(): BaseTypeVisitor<*> {
    return MungoVisitor(this)
  }

  override fun getSupportedOptions() = super.getSupportedOptions().plus(showTypeInfoOpt).plus(configFile)

  fun shouldReportTypeInfo() = hasOption(showTypeInfoOpt)

}
