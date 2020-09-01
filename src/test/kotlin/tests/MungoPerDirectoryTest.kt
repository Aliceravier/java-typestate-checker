package tests

import org.checkerframework.checker.mungo.MainChecker
import org.checkerframework.framework.test.CheckerFrameworkPerDirectoryTest
import org.checkerframework.framework.test.TestConfigurationBuilder
import org.checkerframework.framework.test.TestUtilities
import org.junit.Test
import java.io.File
import java.util.*

val ignore = emptyArray<String>()

abstract class MungoPerDirectoryTest(val originalTestDir: String, testFiles: List<File>, opts: Array<String>) : CheckerFrameworkPerDirectoryTest(
  testFiles,
  MainChecker::class.java,
  originalTestDir,
  *opts
) {
  @Test
  override fun run() {
    if (ignore.contains(originalTestDir)) {
      return
    }
    val shouldEmitDebugInfo = TestUtilities.getShouldEmitDebugInfo()
    val customizedOptions = customizeOptions(Collections.unmodifiableList(checkerOptions))
    val config = TestConfigurationBuilder.buildDefaultConfiguration(
      testDir,
      testFiles,
      setOf(checkerName),
      customizedOptions,
      shouldEmitDebugInfo)
    val testResult = MungoTypecheckExecutor(testDir).runTest(config)
    TestUtilities.assertResultsAreValid(testResult)
  }
}
