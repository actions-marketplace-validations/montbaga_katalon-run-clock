import com.kms.katalon.core.annotation.AfterTestCase
import com.kms.katalon.core.annotation.AfterTestSuite
import com.kms.katalon.core.annotation.BeforeTestCase
import com.kms.katalon.core.annotation.BeforeTestSuite
import com.kms.katalon.core.context.TestCaseContext
import com.kms.katalon.core.context.TestSuiteContext

import runclock.RunClockRecorder

class RunClockListener {

    @BeforeTestSuite
    def beforeTestSuite(TestSuiteContext context) {
        RunClockRecorder.suiteStarted(context.testSuiteId)
    }

    @BeforeTestCase
    def beforeTestCase(TestCaseContext context) {
        RunClockRecorder.caseStarted(context.testCaseId)
    }

    @AfterTestCase
    def afterTestCase(TestCaseContext context) {
        RunClockRecorder.caseEnded(context.testCaseId, context.testCaseStatus)
    }

    @AfterTestSuite
    def afterTestSuite(TestSuiteContext context) {
        RunClockRecorder.suiteEnded(context.testSuiteId, context.status)
    }
}
