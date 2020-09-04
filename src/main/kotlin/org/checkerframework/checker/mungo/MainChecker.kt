package org.checkerframework.checker.mungo

import org.checkerframework.checker.mungo.core.Typechecker
import org.checkerframework.checker.mungo.utils.MungoUtils
import org.checkerframework.framework.source.SourceChecker
import org.checkerframework.framework.source.SourceVisitor

const val showTypeInfoOpt = "showTypeInfo"
const val configFile = "configFile"

class MainChecker : SourceChecker() {

  lateinit var utils: MungoUtils

  override fun getSupportedOptions() = super.getSupportedOptions().plus(showTypeInfoOpt).plus(configFile)

  fun shouldReportTypeInfo() = hasOption(showTypeInfoOpt)

  override fun createSourceVisitor(): SourceVisitor<*, *> {
    return Typechecker(this)
  }

  override fun initChecker() {
    super.initChecker()
    val utils = MungoUtils(this)
    utils.initFactory()
    this.utils = utils
  }

}
