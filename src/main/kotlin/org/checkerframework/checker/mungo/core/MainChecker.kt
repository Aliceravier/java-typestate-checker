package org.checkerframework.checker.mungo.core

import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.framework.source.SourceChecker
import org.checkerframework.framework.source.SourceVisitor

const val showTypeInfoOpt = "showTypeInfo"
const val configFile = "configFile"

class MainChecker : SourceChecker() {

  private val _utils = MungoUtils.LazyField { MungoUtils(this) }
  val utils get() = _utils.get()

  override fun getSupportedOptions() = super.getSupportedOptions().plus(showTypeInfoOpt).plus(configFile)

  fun shouldReportTypeInfo() = hasOption(showTypeInfoOpt)

  override fun createSourceVisitor(): SourceVisitor<*, *> {
    return Typechecker(this)
  }

}
