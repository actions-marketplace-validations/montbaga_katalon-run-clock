package runclock

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.util.KeywordUtil

import groovy.json.JsonOutput

class RunClockRecorder {

    private static final Object LOCK = new Object()
    private static final String MAX_TAG = '<maxConcurrentInstances>'
    private static Path projectDir
    private static Path sessionDir
    private static Path eventFile
    private static boolean disabled
    private static boolean afterSuiteSeen

    static void suiteStarted(String suiteId) {
        record([e: 'suiteStart', suite: suiteId, max: maxConcurrent(suiteId)] as Map<String, Object>)
        Map<String, Object> collection = collectionSetting()
        if (collection != null) {
            record(collection)
        }
    }

    // a collection's Execution mode and Max concurrent instances as they are when the run starts
    private static Map<String, Object> collectionSetting() {
        try {
            Path plan = sessionDir != null ? sessionDir.resolve('plan.jsonl') : null
            if (plan == null || !Files.exists(plan)) {
                return null
            }
            String line = Files.readAllLines(plan, StandardCharsets.UTF_8).find { String l -> l.contains('"executionType"') }
            Map root = line ? (Map) new groovy.json.JsonSlurper().parseText(line) : null
            if (root == null || root.executionType != 'TEST_SUITE_COLLECTION') {
                return null
            }
            String xml = new String(Files.readAllBytes(Paths.get(RunConfiguration.getProjectDir(), root.entityId + '.ts')), StandardCharsets.UTF_8)
            def mode = xml =~ /<executionMode>([^<]+)<\/executionMode>/
            def limit = xml =~ /<maxConcurrentInstances>(\d+)<\/maxConcurrentInstances>/
            return [e: 'collection', mode: mode.find() ? mode.group(1) : null, max: limit.find() ? Integer.valueOf(limit.group(1)) : null] as Map<String, Object>
        } catch (Throwable ignored) {
            return null
        }
    }

    private static Integer maxConcurrent(String suiteId) {
        try {
            Path ts = Paths.get(RunConfiguration.getProjectDir(), suiteId + '.ts')
            String xml = new String(Files.readAllBytes(ts), StandardCharsets.UTF_8)
            int from = xml.indexOf(MAX_TAG)
            int to = xml.indexOf('</maxConcurrentInstances>')
            return from < 0 || to < from ? null : Integer.valueOf(xml.substring(from + MAX_TAG.length(), to).trim())
        } catch (Throwable ignored) {
            return null
        }
    }

    static void suiteEnded(String suiteId, String status) {
        record([e: 'suiteEnd', suite: suiteId, status: status] as Map<String, Object>)
        if (eventFile == null) {
            return
        }
        afterSuiteSeen = true
        // in a collection only the suite that finishes last writes the report; earlier ones would be overwritten
        if (!allSuitesEnded()) {
            KeywordUtil.logInfo('Run Clock: the report is written when the last suite of this collection finishes.')
            return
        }
        long began = System.currentTimeMillis()
        String text = summarize(false)
        record([e: 'report', ms: System.currentTimeMillis() - began] as Map<String, Object>)
        if (text) {
            KeywordUtil.logInfo(text)
        }
    }

    static void caseStarted(String caseId) {
        record([e: 'caseStart', id: caseId] as Map<String, Object>)
    }

    static void caseEnded(String caseId, String status) {
        record([e: 'caseEnd', id: caseId, status: status] as Map<String, Object>)
    }

    private static void record(Map<String, Object> event) {
        synchronized (LOCK) {
            if (disabled) {
                return
            }
            try {
                if (eventFile == null && !open()) {
                    disabled = true
                    return
                }
                event.put('t', System.currentTimeMillis())
                append(event)
            } catch (Throwable e) {
                disabled = true
                System.err.println('[RunClock] recording stopped: ' + e)
            }
        }
    }

    private static boolean open() {
        projectDir = Paths.get(RunConfiguration.getProjectDir()).toAbsolutePath().normalize()
        String reportFolder = RunConfiguration.getReportFolder()
        if (!reportFolder) {
            return false
        }
        Path report = Paths.get(reportFolder).toAbsolutePath().normalize()
        sessionDir = findSessionDir(report)
        if (sessionDir == null) {
            return false
        }
        long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime()
        ProcessHandle self = ProcessHandle.current()
        Path dir = sessionDir.resolve('run-clock')
        Files.createDirectories(dir)
        eventFile = dir.resolve(self.pid() + '-' + jvmStart + '.jsonl')
        append([
            e       : 'process',
            pid     : self.pid(),
            ppid    : self.parent().isPresent() ? self.parent().get().pid() : -1L,
            jvmStart: jvmStart,
            source  : RunConfiguration.getExecutionSourceId(),
            folder  : sessionDir.relativize(report).toString().replace('\\', '/')
        ] as Map<String, Object>)
        Runtime.getRuntime().addShutdownHook(new Thread({ onExit() } as Runnable, 'run-clock-exit'))
        return true
    }

    private static Path findSessionDir(Path report) {
        Path reports = projectDir.resolve('Reports')
        if (report.startsWith(reports) && report.getNameCount() > reports.getNameCount()) {
            return reports.resolve(report.getName(reports.getNameCount()))
        }
        for (Path p = report; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve('plan.jsonl'))) {
                return p
            }
        }
        return null
    }

    private static void append(Map<String, Object> event) {
        Files.write(eventFile, (JsonOutput.toJson(event) + '\n').getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    private static void onExit() {
        try {
            synchronized (LOCK) {
                append([e: 'exit', t: System.currentTimeMillis()] as Map<String, Object>)
            }
            if (afterSuiteSeen && allSuitesEnded() && summarize(true) != null && runComplete) {
                RunClockSettings settings = RunClockSettings.load(projectDir, System.getenv())
                if (shouldOpen(settings.openReport, parentCommand(), noDesktop(), onCi()) && RunClockReport.claim(sessionDir.resolve('run-clock').resolve('.opened'))) {
                    openInBrowser(sessionDir.resolve('run-clock').resolve('summary.html'))
                }
                lastReport.sendChat()
                RunClockReport.writeUnfinished(projectDir, sessionDir)
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean runComplete
    private static RunClockReport lastReport

    // false only for a collection with a planned suite that has not reached its after-suite listeners
    private static boolean allSuitesEnded() {
        try {
            Path plan = sessionDir.resolve('plan.jsonl')
            if (!Files.exists(plan)) {
                return true
            }
            String line = Files.readAllLines(plan, StandardCharsets.UTF_8).reverse().find { String l -> l.contains('"execution"') }
            Map root = line ? (Map) new groovy.json.JsonSlurper().parseText(line) : null
            if (root == null || root.executionType != 'TEST_SUITE_COLLECTION') {
                return true
            }
            List<List<String>> planned = []
            attempts((Map) root.execution, planned)
            List<String> ended = []
            Files.newDirectoryStream(sessionDir.resolve('run-clock'), '*.jsonl').withCloseable { stream ->
                for (Path file : stream) {
                    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                    int firstLine = text.indexOf('\n')
                    if (firstLine > 0 && text.contains('"e":"suiteEnd"')) {
                        Object folder = ((Map) new groovy.json.JsonSlurper().parseText(text.substring(0, firstLine))).folder
                        if (folder != null) {
                            ended << (String) folder
                        }
                    }
                }
            }
            return planned.every { List<String> dirs -> dirs.any { String d -> ended.any { String f -> f == d || f.startsWith(d + '/') } } }
        } catch (Throwable ignored) {
            return true
        }
    }

    // the report folders of each planned suite run, one list per suite
    private static void attempts(Map node, List<List<String>> planned) {
        if (node.kind == 'TEST_SUITE') {
            planned << ((List) (node.children ?: [])).findAll { it instanceof Map && it.kind == 'TEST_SUITE_ATTEMPT' && it.executionDirPath }
                    .collect { Map a -> ((String) a.executionDirPath).replace('\\', '/') }
            return
        }
        for (Object child : (List) (node.children ?: [])) {
            if (child instanceof Map) {
                attempts((Map) child, planned)
            }
        }
    }

    private static String summarize(boolean exiting) {
        try {
            RunClockReport report = new RunClockReport(projectDir, sessionDir)
            String text = report.write(exiting)
            runComplete = report.isComplete()
            lastReport = report
            return text
        } catch (Throwable e) {
            System.err.println('[RunClock] summary failed: ' + e)
            return null
        }
    }

    private static boolean noDesktop() {
        String os = System.getProperty('os.name', '').toLowerCase()
        return !os.contains('win') && !os.contains('mac') && !System.getenv('DISPLAY') && !System.getenv('WAYLAND_DISPLAY')
    }

    private static boolean onCi() {
        return ['CI', 'JENKINS_URL', 'TF_BUILD', 'GITLAB_CI', 'GITHUB_ACTIONS', 'TEAMCITY_VERSION', 'BITBUCKET_BUILD_NUMBER']
                .any { String name -> System.getenv(name) != null }
    }

    private static String parentCommand() {
        try {
            Optional<ProcessHandle> parent = ProcessHandle.current().parent()
            return parent.isPresent() ? parent.get().info().command().orElse('') : ''
        } catch (Throwable ignored) {
            return ''
        }
    }

    // studio opens only when Katalon Studio itself started the run on a desktop; Runtime Engine and CI never open
    static boolean shouldOpen(String setting, String parent, boolean noDesktop, boolean ci) {
        if (noDesktop || setting == 'never') {
            return false
        }
        if (setting == 'always') {
            return true
        }
        String name = Paths.get(parent ?: 'none').fileName.toString().toLowerCase()
        return !ci && name.startsWith('katalon') && !name.startsWith('katalonc')
    }

    private static void openInBrowser(Path page) {
        try {
            String os = System.getProperty('os.name', '').toLowerCase()
            List<String> command = os.contains('win') ? ['rundll32', 'url.dll,FileProtocolHandler', page.toString()] :
                    (os.contains('mac') ? ['open', page.toString()] : ['xdg-open', page.toString()])
            new ProcessBuilder(command).start()
        } catch (Throwable e) {
            System.err.println('[RunClock] could not open the report: ' + e)
        }
    }
}
