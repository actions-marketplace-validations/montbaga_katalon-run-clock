package runclock

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

class RunClockReport {

    static final String VERSION = '1.0.0'
    private static final int HISTORY_LIMIT = 1000
    private static final int HISTORY_CAP = 5000
    private static final int TEST_RUNS = 20
    private static final int TREND = 20
    private static final long SPLIT_MIN_MS = 60000L
    private static final int SAMPLE = 5
    private static final long LOCK_WAIT_MS = 10000L
    private static final long MAX_PREP_MS = 10 * 60 * 1000L
    private static final long QUIET_MS = 10 * 60 * 1000L
    private static final int UNFINISHED = 5

    static class Run {
        String id
        String status
        long start
        long end

        long duration() { end - start }
    }

    static class Proc {
        long pid
        long jvmStart
        String source
        String folder
        String eventSuite
        Integer max
        String suite
        Long first
        Long last
        Long exit
        boolean before
        boolean after
        long own
        String group
        String suiteLabel
        String tscMode
        Integer tscMax
        List<Run> runs = []

        long end() { exit != null ? exit : (last != null ? last : jvmStart) }

        Long startup() { first != null ? first - jvmStart : null }

        Long shutdown() { exit != null && last != null ? exit - last : null }
    }

    static class Suite {
        String id
        String key
        String label
        List<Proc> procs = []

        List<Proc> testProcs() { procs.findAll { Proc p -> !p.runs.empty } }

        List<Run> runs() { (List<Run>) procs.collectMany { Proc p -> p.runs } }

        long start() { procs.collect { Proc p -> p.jvmStart }.min() }

        long end() { procs.collect { Proc p -> p.end() }.max() }

        long own() {
            long total = 0L
            for (Proc p : procs) {
                total += p.own
            }
            return total
        }

        // this suite's time without Run Clock's own report writing
        long span() { end() - start() - own() }

        boolean parallel() { testProcs().size() > 1 || procs.any { Proc p -> p.runs.empty } }
    }

    static class Obs {
        String session
        String suite
        String key
        int k
        long at
        long elapsed
        long before
        long startup
        long shutdown
        long fixed
        List<Long> tests = []

        Map<String, Object> toMap() {
            [session: session, suite: suite, key: key, k: k, at: at, elapsed: elapsed, before: before,
             startup: startup, shutdown: shutdown, fixed: fixed, tests: tests] as Map<String, Object>
        }

        static Obs fromMap(Map m) {
            new Obs(session: (String) m.get('session'), suite: (String) m.get('suite'), key: (String) m.get('key'),
                    k: (int) RunClockReport.num(m, 'k'), at: RunClockReport.num(m, 'at'), elapsed: RunClockReport.num(m, 'elapsed'), before: RunClockReport.num(m, 'before'),
                    startup: RunClockReport.num(m, 'startup'), shutdown: RunClockReport.num(m, 'shutdown'), fixed: RunClockReport.num(m, 'fixed'),
                    tests: ((List) m.get('tests') ?: []).collect { Object t -> ((Number) t).longValue() })
        }
    }

    static class Option {
        String key
        int k
        long millis
        int runs
        boolean measured
        boolean current
    }

    private final Path projectDir
    private final Path sessionDir
    private final RunClockSettings settings
    private final Path historyDir
    private final Path historyFile
    private String message
    private final JsonSlurper json = new JsonSlurper()
    private String planRoot
    private String planType
    // each run of a suite in the plan, by its plan id: a collection may run the same suite twice
    private final Map<String, String> planRuns = new LinkedHashMap<>()
    private final Map<String, String> runDirs = [:]
    private boolean complete
    private boolean lockTimedOut

    RunClockReport(Path projectDir, Path sessionDir) {
        this(projectDir, sessionDir, RunClockSettings.load(projectDir, System.getenv()))
    }

    RunClockReport(Path projectDir, Path sessionDir, RunClockSettings settings) {
        this.projectDir = projectDir
        this.sessionDir = sessionDir
        this.settings = settings
        this.historyDir = settings.historyDir
        this.historyFile = historyDir.resolve('history.jsonl')
    }

    // one line for chat tools, set by write()
    String chatMessage() {
        message
    }

    // true once every suite of the run has reached its after-suite listeners
    boolean isComplete() {
        complete
    }

    String write(boolean exiting) {
        List<Proc> procs = readProcesses()
        if (procs.empty) {
            return null
        }
        long start = sessionStart(procs)
        long end = procs.collect { Proc p -> p.end() }.max()
        if (!exiting) {
            end = Math.max(end, System.currentTimeMillis())
        }
        List<Suite> suites = group(procs)
        // real time leaves out Run Clock's own report writing; end stays the wall clock for the timeline
        long ownClock = ownOnClock(suites, procs, end)
        long runEnd = end - ownClock
        Map<String, Object> model = [
                version  : VERSION,
                generated: System.currentTimeMillis(),
                session  : sessionDir.fileName.toString(),
                start    : start,
                end      : end,
                'final'  : exiting,
                stats    : stats(procs, start, end, ownClock),
                procs    : procs.collect { Proc p -> procModel(p) }
        ] as Map<String, Object>
        // the plan lists every suite of a collection from the start, so the first suite to finish is not taken for a single run
        boolean manySuites = suites.size() > 1 || planType == 'TEST_SUITE_COLLECTION' || planRuns.size() > 1
        Collection<String> expected = planRuns ? planRuns.keySet() : suites.collect { Suite s -> s.key }
        complete = expected.every { String key -> suites.any { Suite s -> s.key == key && s.procs.any { Proc p -> p.after } } }
        String text
        if (!manySuites) {
            Suite suite = suites[0]
            Obs cur = observe(suite, start, runEnd)
            List<Obs> past = historyFor(cur)
            List<Option> opts = cur.tests.size() > 1 ? options(cur, past) : []
            Map<String, Object> advice = opts ? advice(opts, cur) : null
            Map<String, Object> slow = slowerThanUsual(cur, past)
            text = single(suite, cur, opts, advice, slow)
            model.putAll([
                    kind   : 'suite',
                    title  : name(suite.id),
                    entity : suite.id,
                    mode   : [parallel: suite.parallel(), max: cur.k, peak: peak(suite.testProcs())],
                    options: opts.collect { Option o ->
                        [label   : label(o), key: o.key, k: o.k, millis: o.millis, runs: o.runs, measured: o.measured,
                         current : o.current, fastest: advice != null && o.key == advice.key]
                    },
                    advice : advice,
                    slower : slow,
                    tests  : testInsights(suite, cur),
                    split  : splitPlan(suite)
            ])
            message = name(suite.id) + ': ' + secs(cur.elapsed) + ' of real time with ' + label(cur.key, cur.k) + '.' +
                    (advice != null && !advice.currentFastest ? ' Fastest setting: ' + advice.fastest + '.' : '') +
                    (slow != null ? ' ' + slow.percent + '% slower than usual.' : '')
        } else {
            Map<String, Object> plan = collectionAdvice(suites, start, runEnd, earlierRuns(planRoot ?: '', suites.size()))
            text = collection(suites, start, runEnd, plan)
            message = name(planRoot ?: sessionDir.fileName.toString()) + ': ' + secs(runEnd - start) + ' of real time for ' + suites.size() +
                    (suites.size() == 1 ? ' suite.' : ' suites.') + (plan != null && plan.advice ? ' ' + plan.advice : '')
            model.putAll([
                    kind  : 'collection',
                    title : name(planRoot ?: sessionDir.fileName.toString()),
                    entity: planRoot ?: '',
                    suites: suites.collect { Suite s ->
                        [id   : s.id, key: s.key, name: s.label, start: s.start(), end: s.end(), elapsed: s.span(),
                         processes: s.procs.size(), tests: s.runs().size()]
                    },
                    plan  : plan,
                    tests : slowestAcross(suites)
            ])
        }
        model.summary = text
        model.complete = complete
        model.page = 'run'
        model.history = settings.history
        model.slowerBy = settings.slowerBy
        Path dir = sessionDir.resolve('run-clock')
        boolean listed = false
        try {
            listed = listRun(model, end, dir)
        } catch (Exception e) {
            System.err.println('[RunClock] run list not updated: ' + e)
        }
        writeAtomic(dir.resolve('summary.txt'), text)
        writeAtomic(dir.resolve('summary.html'), html(model))
        if (settings.ciSummary) {
            ciSummary(model, dir, exiting && complete)
        }
        return text + '\n\nThis run: ' + dir.resolve('summary.html') + (listed ? '\nAll runs: ' + historyDir.resolve('index.html') : '')
    }

    // a run stopped before its last suite finished has no report; the next finished run in the project writes it
    static void writeUnfinished(Path projectDir, Path sessionDir) {
        try {
            String current = sessionDir.fileName.toString()
            long quietSince = System.currentTimeMillis() - QUIET_MS
            List<Path> unfinished = []
            Files.newDirectoryStream(sessionDir.parent).withCloseable { stream ->
                for (Path session : stream) {
                    Path dir = session.resolve('run-clock')
                    if (session.fileName.toString() < current && Files.isDirectory(dir) && !Files.exists(dir.resolve('summary.html')) &&
                            lastChange(dir) < quietSince) {
                        unfinished << session
                    }
                }
            }
            for (Path session : unfinished.toSorted { Path p -> p.fileName.toString() }.takeRight(UNFINISHED)) {
                try {
                    new RunClockReport(projectDir, session).write(true)
                } catch (Exception e) {
                    System.err.println('[RunClock] report of ' + session.fileName + ' not written: ' + e)
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static long lastChange(Path dir) {
        long last = 0L
        Files.newDirectoryStream(dir).withCloseable { stream ->
            for (Path file : stream) {
                last = Math.max(last, Files.getLastModifiedTime(file).toMillis())
            }
        }
        return last
    }

    // history on: learn from this run; readonly: compare with earlier runs without adding this one; off: this run alone
    private List<Obs> historyFor(Obs cur) {
        if (settings.history == 'off') {
            return [cur]
        }
        if (settings.history == 'readonly') {
            List<Obs> mine = readHistory().findAll { Obs o -> o.suite == cur.suite && o.session != cur.session }
            mine << cur
            return mine
        }
        try {
            return updateHistory(cur)
        } catch (Exception e) {
            System.err.println('[RunClock] history not updated: ' + e)
            return [cur]
        }
    }

    // the run list and All runs page: written when history is on, only read when readonly, skipped when off
    private boolean listRun(Map<String, Object> model, long end, Path dir) {
        if (settings.history == 'off') {
            return false
        }
        boolean writing = settings.history == 'on'
        Map<String, Object> entry = runEntry(model)
        entry.dir = sessionDir.toAbsolutePath().normalize().toString()
        // a CI agent never has earlier builds' folders, so its runs stay listed without them
        if (settings.onCi()) {
            entry.ci = true
        }
        Path home = historyDir.resolve('index.html')
        Closure body = {
            List<Map> runs = writing ? updateRuns(entry) : readRuns(entry)
            Map previous = runs.findAll { Map r -> r.entity == entry.entity && r.session != entry.session && num(r, 'at') < end }
                    .max { Map r -> num(r, 'at') }
            if (writing || Files.exists(home)) {
                model.home = link(dir, home)
            }
            if (previous != null) {
                model.previous = [session: previous.session, setting: previous.setting, elapsed: previous.elapsed,
                                  at     : previous.at, href: pageHref(dir, previous, entry)]
            }
            List<Map> mine = runs.findAll { Map r -> r.entity == entry.entity }
            model.trend = (mine.size() > TREND ? mine.subList(mine.size() - TREND, mine.size()) : mine).collect { Map r ->
                [session: r.session, at: r.at, setting: r.setting, elapsed: r.elapsed, runs: r.runs, failed: r.failed,
                 current: r.session == entry.session, href: pageHref(dir, r, entry)]
            }
            if (writing) {
                List<Map> newest = runs.reverse().collect { Map r -> (r.findAll { k, v -> k != 'dir' } as Map) + [href: pageHref(historyDir, r, entry)] }
                writeAtomic(home, html([page: 'home', version: VERSION, generated: System.currentTimeMillis(),
                                        project: projectDir.fileName.toString(), history: settings.history, runs: newest] as Map<String, Object>))
            }
            return null
        }
        if (writing) {
            withLock(body)
        } else {
            body.call()
        }
        return writing
    }

    private List<Map> readRuns(Map<String, Object> entry) {
        List<Map> runs = readJsonLines(historyDir.resolve('runs.jsonl')).findAll { Map r -> r.session != entry.session }
        runs << entry
        return runs.toSorted { Map r -> num(r, 'at') }
    }

    private List<Map> readJsonLines(Path file) {
        List<Map> out = []
        if (!Files.exists(file)) {
            return out
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            try {
                Object o = json.parseText(line)
                if (o instanceof Map) {
                    out << (Map) o
                }
            } catch (Exception ignored) {
            }
        }
        return out
    }

    // a run's report page, wherever its session folder is now
    private Path sessionPage(Map r) {
        Path folder = null
        try {
            folder = r.dir ? Paths.get((String) r.dir) : null
        } catch (Exception ignored) {
        }
        if (folder == null || !Files.isDirectory(folder)) {
            folder = projectDir.resolve('Reports').resolve((String) r.session)
        }
        return folder.resolve('run-clock').resolve('summary.html')
    }

    // null when a run's report is not on this machine, such as one from an earlier CI build
    private String pageHref(Path fromDir, Map r, Map entry) {
        Path page = sessionPage(r)
        return r.session == entry.session || Files.exists(page) ? link(fromDir, page) : null
    }

    // a relative link that still works when the project folder moves; an absolute file link across drives
    private static String link(Path fromDir, Path target) {
        Path a = fromDir.toAbsolutePath().normalize()
        Path b = target.toAbsolutePath().normalize()
        try {
            return a.relativize(b).toString().replace('\\', '/')
        } catch (IllegalArgumentException ignored) {
            return b.toUri().toString()
        }
    }

    // this run against the median of the previous SAMPLE runs with the same setting
    private Map<String, Object> slowerThanUsual(Obs cur, List<Obs> past) {
        List<Obs> same = past.findAll { Obs o -> o.key == cur.key && o.session != cur.session }.toSorted { Obs o -> -o.at }
        if (same.size() < 3) {
            return null
        }
        List<Long> times = (same.size() > SAMPLE ? same.subList(0, SAMPLE) : same).collect { Obs o -> o.elapsed }
        long usual = middle(times)
        long allowed = usual + (usual * settings.slowerBy).intdiv(100)
        if (cur.elapsed <= allowed || cur.elapsed - usual < 2000L) {
            return null
        }
        return [median : usual, runs: times.size(), percent: Math.round((cur.elapsed - usual) * 100d / usual),
                setting: label(cur.key, cur.k)] as Map<String, Object>
    }

    // each test's time in this run, with its usual time from the suite's last TEST_RUNS runs
    private List<Map<String, Object>> testInsights(Suite suite, Obs cur) {
        Map<String, Long> now = new LinkedHashMap<>()
        Map<String, String> status = [:]
        for (Run r : suite.runs()) {
            Long seen = now.get(r.id)
            now.put(r.id, (seen != null ? seen : 0L) + r.duration())
            status.put(r.id, r.status)
        }
        Map<String, List<Long>> earlier = [:]
        if (settings.history != 'off' && now) {
            try {
                Path file = testsFile(suite.id)
                for (Map line : readJsonLines(file).toSorted { Map l -> num(l, 'at') }) {
                    if (line.session == cur.session || !(line.t instanceof Map)) {
                        continue
                    }
                    for (Map.Entry e : ((Map) line.t).entrySet()) {
                        List<Long> list = earlier.get((String) e.key)
                        if (list == null) {
                            list = []
                            earlier.put((String) e.key, list)
                        }
                        list << ((Number) e.value).longValue()
                    }
                }
                if (settings.history == 'on') {
                    withLock {
                        List<Map> kept = readJsonLines(file).findAll { Map l -> l.session != cur.session }
                        kept << ([session: cur.session, at: cur.at, key: cur.key, t: now] as Map)
                        kept = kept.toSorted { Map l -> num(l, 'at') }
                        if (kept.size() > TEST_RUNS) {
                            kept = kept.subList(kept.size() - TEST_RUNS, kept.size())
                        }
                        writeAtomic(file, kept.collect { Map l -> JsonOutput.toJson(l) }.join('\n') + '\n')
                        return null
                    }
                }
            } catch (Exception e) {
                System.err.println('[RunClock] test history not updated: ' + e)
            }
        }
        List<Map<String, Object>> out = []
        for (Map.Entry<String, Long> e : now.entrySet()) {
            List<Long> prev = earlier.get(e.key) ?: []
            List<Long> recent = prev.size() > 10 ? prev.subList(prev.size() - 10, prev.size()) : prev
            out << ([id: e.key, name: name(e.key), ms: e.value, status: status.get(e.key), runs: recent.size(),
                     usual: recent.size() >= 3 ? middle(recent) : null] as Map<String, Object>)
        }
        out.sort { Map a, Map b -> num(b, 'ms') <=> num(a, 'ms') }
        return out
    }

    private Path testsFile(String suiteId) {
        String safe = name(suiteId).replaceAll('[^A-Za-z0-9._-]', '_')
        return historyDir.resolve('tests').resolve(safe + '-' + Integer.toHexString(suiteId.hashCode()) + '.jsonl')
    }

    // balanced parts by test time, for spreading one long suite over several machines
    private static Map<String, Object> splitPlan(Suite suite) {
        Map<String, Long> per = new LinkedHashMap<>()
        for (Run r : suite.runs()) {
            Long seen = per.get(r.id)
            per.put(r.id, (seen != null ? seen : 0L) + r.duration())
        }
        long total = 0L
        for (Long v : per.values()) {
            total += v
        }
        if (per.size() < 6 || total < SPLIT_MIN_MS) {
            return null
        }
        List<Map.Entry<String, Long>> order = new ArrayList<Map.Entry<String, Long>>(per.entrySet())
        order.sort { Map.Entry<String, Long> a, Map.Entry<String, Long> b -> b.value <=> a.value }
        List<Map<String, Object>> plans = []
        for (int k = 2; k <= 4 && k <= per.size(); k++) {
            long[] load = new long[k]
            List<List<String>> parts = []
            for (int j = 0; j < k; j++) {
                parts << new ArrayList<String>()
            }
            for (Map.Entry<String, Long> e : order) {
                int min = 0
                for (int j = 1; j < k; j++) {
                    if (load[j] < load[min]) {
                        min = j
                    }
                }
                load[min] = load[min] + e.value
                parts[min] << name(e.key)
            }
            long longest = 0L
            for (int j = 0; j < k; j++) {
                longest = Math.max(longest, load[j])
            }
            plans << ([parts: k, longest: longest, loads: load.toList(), tests: parts] as Map<String, Object>)
        }
        return [total: total, tests: per.size(), plans: plans] as Map<String, Object>
    }

    // finished earlier runs of this collection with the same number of suites, newest first
    private List<Map> earlierRuns(String entity, int count) {
        if (settings.history == 'off' || !entity) {
            return []
        }
        try {
            String session = sessionDir.fileName.toString()
            return readJsonLines(historyDir.resolve('runs.jsonl')).findAll { Map r ->
                r.entity == entity && r.session != session && r.key && num(r, 'count') == count && r.complete != false
            }.toSorted { Map r -> -num(r, 'at') }
        } catch (Exception ignored) {
            return []
        }
    }

    // the collection's own setting: one suite after another against several at once
    private Map<String, Object> collectionAdvice(List<Suite> suites, long start, long end, List<Map> earlier) {
        if (suites.size() < 2) {
            return null
        }
        List<Long> durations = suites.collect { Suite s -> s.span() }
        boolean together = false
        for (int i = 0; i < suites.size(); i++) {
            for (int j = i + 1; j < suites.size(); j++) {
                if (suites[i].start() < suites[j].end() && suites[j].start() < suites[i].end()) {
                    together = true
                }
            }
        }
        long before = suites.collect { Suite s -> s.start() }.min() - start
        long sum = 0L
        for (Long d : durations) {
            sum += d
        }
        Map<String, Object> tsc = collectionSettings(suites)
        int n = suites.size()
        int running = together ? Math.max(2, Math.min(tsc.max != null ? (int) tsc.max : n, n)) : 1
        String curKey = together ? 'parallel:' + running : 'sequential'
        Set<Integer> ks = new TreeSet<Integer>()
        for (int k = 2; k <= Math.min(4, n); k++) {
            ks << k
        }
        if (together) {
            ks << running
        }
        for (Map r : earlier) {
            String rk = (String) r.key
            if (rk.startsWith('parallel:') && Integer.parseInt(rk.substring(9)) <= n) {
                ks << Integer.parseInt(rk.substring(9))
            }
        }
        List<Map<String, Object>> options = []
        options << collectionOption('sequential', 'One suite after another', together ? before + sum : end - start, curKey, end - start, earlier)
        for (Integer k : ks) {
            options << collectionOption('parallel:' + k, k == n ? 'All ' + n + ' at once' : k + ' suites at once',
                    before + makespan(durations, k, 0L), curKey, end - start, earlier)
        }
        Map<String, Object> best = options.min { Map o -> num(o, 'millis') }
        Map<String, Object> cur = options.find { Map o -> o.current }
        String advice = null
        if (!best.is(cur) && num(best, 'millis') < num(cur, 'millis') * 0.85) {
            String about = best.measured ? ' was faster: ' + secs(num(best, 'millis')) + (num(best, 'runs') > 1 ? ' (median of ' + best.runs + ' runs)' : '') :
                    ' may be faster: about ' + secs(num(best, 'millis'))
            advice = best.key == 'sequential' ?
                    'One suite after another' + about + ' instead of ' + secs(num(cur, 'millis')) + '. Set Execution mode to Sequential in the collection.' :
                    'Running ' + atOnce(Integer.parseInt(((String) best.key).substring(9)), n) + ' at once' + about +
                            ' instead of ' + secs(num(cur, 'millis')) + '. Set Execution mode to Parallel with Max concurrent instances ' +
                            ((String) best.key).substring(9) + ' in the collection' + (best.measured ? '.' : ', then run it once to measure.')
        }
        boolean measuredBefore = options.any { Map o -> o.measured && !o.current }
        String setup = (tsc.mode == 'PARALLEL' || (tsc.mode == null && together) ? 'Parallel, max ' + (tsc.max ?: running) : 'Sequential') + ', ' + n + ' suites'
        return [together: together, mode: tsc.mode, max: tsc.max, key: curKey, setting: setup, options: options, advice: advice,
                note    : (together ? 'These suites ran at the same time and slowed each other down, so estimates from this run are upper estimates.' :
                        'The at-once estimates assume suites do not slow each other down. On one machine they usually do, so treat them as best cases.') +
                        (measuredBefore ? ' A setting you have run shows the median of its last ' + SAMPLE + ' runs of this collection.' : '')] as Map<String, Object>
    }

    // a measured setting is the median of its last SAMPLE runs, this run included; otherwise the estimate
    private static Map<String, Object> collectionOption(String key, String label, long estimate, String current, long now, List<Map> earlier) {
        List<Long> times = key == current ? [now] : []
        for (Map r : earlier) {
            if (r.key == key && times.size() < SAMPLE) {
                times << num(r, 'elapsed')
            }
        }
        return [key: key, label: label, millis: times ? middle(times) : estimate, measured: !times.empty, runs: times.size(),
                current: key == current] as Map<String, Object>
    }

    private static String atOnce(int k, int n) {
        k < n ? k + ' suites' : (n == 2 ? 'both suites' : 'all ' + n + ' suites')
    }

    // the setting recorded when the run started; the collection file only for runs recorded before that existed
    private Map<String, Object> collectionSettings(List<Suite> suites) {
        Map<String, Object> out = [mode: null, max: null] as Map<String, Object>
        for (Suite s : suites) {
            for (Proc p : s.procs) {
                if (p.tscMode != null) {
                    out.mode = p.tscMode
                    out.max = p.tscMax
                    return out
                }
            }
        }
        if (!planRoot) {
            return out
        }
        try {
            String xml = new String(Files.readAllBytes(projectDir.resolve(planRoot + '.ts')), StandardCharsets.UTF_8)
            def mode = xml =~ /<executionMode>([^<]+)<\/executionMode>/
            if (mode.find()) {
                out.mode = mode.group(1)
            }
            def limit = xml =~ /<maxConcurrentInstances>(\d+)<\/maxConcurrentInstances>/
            if (limit.find()) {
                out.max = Integer.valueOf(limit.group(1))
            }
        } catch (Exception ignored) {
        }
        return out
    }

    private static List<Map<String, Object>> slowestAcross(List<Suite> suites) {
        List<Map<String, Object>> all = []
        for (Suite s : suites) {
            for (Run r : s.runs()) {
                all << ([id: r.id, name: name(r.id), suite: s.label, ms: r.duration(), status: r.status] as Map<String, Object>)
            }
        }
        all.sort { Map a, Map b -> num(b, 'ms') <=> num(a, 'ms') }
        return all.size() > 50 ? new ArrayList<Map<String, Object>>(all.subList(0, 50)) : all
    }

    // summary.md beside the report; at the end of the run, also the latest summary and the GitHub Actions job summary
    private void ciSummary(Map<String, Object> model, Path dir, boolean finished) {
        try {
            String md = markdown(model)
            writeAtomic(dir.resolve('summary.md'), md)
            if (finished && claim(dir.resolve('.ci-summary'))) {
                writeAtomic(historyDir.resolve('ci-summary.md'), md)
                String github = settings.env.get('GITHUB_STEP_SUMMARY')
                if (github) {
                    Files.write(Paths.get(github), (md + '\n').getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                }
            }
        } catch (Exception e) {
            System.err.println('[RunClock] CI summary not written: ' + e)
        }
    }

    private static String markdown(Map<String, Object> model) {
        Map stats = (Map) model.stats
        Map plan = (Map) model.plan
        StringBuilder md = new StringBuilder()
        md << '### Run Clock: ' + model.title + '\n\n| | |\n|---|---|\n'
        md << '| Real time | ' + secs(num(stats, 'elapsed')) + ' |\n'
        if (model.kind == 'suite') {
            Map mode = (Map) model.mode
            md << '| Setting | ' + (mode.parallel ? 'Parallel, max ' + mode.max : 'Parallel off') + ' |\n'
        } else {
            md << (plan ? '| Setting | ' + plan.setting + ' |\n' : '| Suites | ' + ((List) model.suites).size() + ' |\n')
        }
        md << '| Start-up per process | ' + secs((Long) stats.startup) + ' |\n'
        md << '| Tests | ' + stats.runs + ' runs, ' + secs(num(stats, 'tests')) + ' |\n'
        Map advice = (Map) model.advice
        if (advice) {
            md << '\n**Fastest setting:** ' + advice.fastest + (advice.currentFastest ? ', used by this run.' : '. ' + advice.action) + '\n'
        }
        Map slow = (Map) model.slower
        if (slow) {
            md << '\n**' + slow.percent + '% slower than usual** for ' + slow.setting + ': the median of the last ' + slow.runs + ' runs is ' + secs(num(slow, 'median')) + '.\n'
        }
        if (plan && plan.advice) {
            md << '\n' + plan.advice + '\n'
        }
        return md.toString()
    }

    // posts the one-line summary to a Slack-compatible incoming webhook, once per run
    boolean sendChat() {
        String url = settings.webhookUrl
        if (!url || message == null || !(url ==~ /(?i)https?:\/\/\S+/)) {
            return false
        }
        if (!claim(sessionDir.resolve('run-clock').resolve('.chat-sent'))) {
            return false
        }
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection()
            c.setConnectTimeout(5000)
            c.setReadTimeout(5000)
            c.setRequestMethod('POST')
            c.setDoOutput(true)
            c.setRequestProperty('Content-Type', 'application/json; charset=utf-8')
            OutputStream os = c.getOutputStream()
            try {
                os.write(JsonOutput.toJson([text: 'Run Clock: ' + message]).getBytes(StandardCharsets.UTF_8))
            } finally {
                os.close()
            }
            int code = c.getResponseCode()
            c.disconnect()
            return code >= 200 && code < 300
        } catch (Exception e) {
            System.err.println('[RunClock] chat message not sent: ' + e)
            return false
        }
    }

    // true for the first process to create the marker, so a run is announced or opened once
    static boolean claim(Path marker) {
        try {
            Files.createDirectories(marker.parent)
            Files.createFile(marker)
            return true
        } catch (FileAlreadyExistsException ignored) {
            return false
        } catch (Exception ignored) {
            return false
        }
    }

    private List<Proc> readProcesses() {
        List<Proc> procs = []
        Path dir = sessionDir.resolve('run-clock')
        if (!Files.isDirectory(dir)) {
            return procs
        }
        DirectoryStream<Path> files = Files.newDirectoryStream(dir, '*.jsonl')
        try {
            for (Path f : files) {
                Proc p = readProcess(f)
                if (p != null) {
                    procs << p
                }
            }
        } finally {
            files.close()
        }
        Map<String, String> dirs = planDirs()
        for (Proc p : procs) {
            p.suite = suiteOf(p, dirs)
            p.group = match(p, runDirs) ?: p.suite
        }
        procs.sort { Proc p -> p.jvmStart }
        return procs
    }

    private Proc readProcess(Path file) {
        Proc proc = null
        Run open = null
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Map m
            try {
                m = (Map) json.parseText(line)
            } catch (Exception ignored) {
                continue
            }
            String e = (String) m.get('e')
            if (e == 'process') {
                proc = new Proc(pid: num(m, 'pid'), jvmStart: num(m, 'jvmStart'),
                        source: (String) m.get('source'), folder: (String) m.get('folder'))
                continue
            }
            if (proc == null) {
                continue
            }
            if (e == 'collection') {
                proc.tscMode = (String) m.get('mode')
                proc.tscMax = m.get('max') instanceof Number ? ((Number) m.get('max')).intValue() : null
                continue
            }
            long t = num(m, 't')
            if (e == 'exit') {
                proc.exit = t
                continue
            }
            if (e == 'report') {
                proc.own += num(m, 'ms')
            }
            if (proc.first == null) {
                proc.first = t
            }
            proc.last = t
            switch (e) {
                case 'suiteStart':
                    proc.before = true
                    proc.eventSuite = proc.eventSuite ?: (String) m.get('suite')
                    proc.max = m.get('max') instanceof Number ? ((Number) m.get('max')).intValue() : null
                    break
                case 'suiteEnd':
                    proc.after = true
                    proc.eventSuite = proc.eventSuite ?: (String) m.get('suite')
                    break
                case 'caseStart':
                    open = new Run(id: (String) m.get('id'), start: t)
                    break
                case 'caseEnd':
                    if (open != null) {
                        open.end = t
                        open.status = (String) m.get('status')
                        proc.runs << open
                        open = null
                    }
                    break
            }
        }
        if (proc != null && open != null) {
            open.end = proc.exit != null ? proc.exit : (proc.last != null ? proc.last : open.start)
            open.status = 'INCOMPLETE'
            proc.runs << open
        }
        return proc
    }

    private Map<String, String> planDirs() {
        Map<String, String> dirs = [:]
        Path plan = sessionDir.resolve('plan.jsonl')
        if (!Files.exists(plan)) {
            return dirs
        }
        List<String> lines = Files.readAllLines(plan, StandardCharsets.UTF_8)
        for (int i = lines.size() - 1; i >= 0; i--) {
            try {
                Map root = (Map) json.parseText(lines[i])
                Map execution = (Map) root.get('execution')
                if (execution != null) {
                    planRoot = (String) root.get('entityId')
                    planType = (String) root.get('executionType')
                    walk(execution, null, null, dirs, runDirs, planRuns)
                    break
                }
            } catch (Exception ignored) {
            }
        }
        return dirs
    }

    private static void walk(Map node, String suite, String run, Map<String, String> dirs, Map<String, String> runDirs, Map<String, String> runs) {
        boolean isSuite = node.get('kind') == 'TEST_SUITE'
        String current = isSuite ? (String) node.get('entityId') : suite
        String instance = run
        if (isSuite && current) {
            instance = node.get('id') ? String.valueOf(node.get('id')) : current + '#' + runs.size()
            runs.put(instance, current)
        }
        String dir = (String) node.get('executionDirPath')
        if (dir && current) {
            dirs.put(dir.replace('\\', '/'), current)
            if (instance) {
                runDirs.put(dir.replace('\\', '/'), instance)
            }
        }
        Object children = node.get('children')
        if (children instanceof List) {
            for (Object child : (List) children) {
                if (child instanceof Map) {
                    walk((Map) child, current, instance, dirs, runDirs, runs)
                }
            }
        }
    }

    private static String suiteOf(Proc p, Map<String, String> dirs) {
        return match(p, dirs) ?: p.eventSuite ?: p.source ?: 'unknown'
    }

    // the value of the longest plan folder that holds this process's report folder
    private static String match(Proc p, Map<String, String> dirs) {
        String folder = p.folder ?: ''
        String best = null
        int length = -1
        for (Map.Entry<String, String> entry : dirs.entrySet()) {
            String dir = entry.key
            if ((folder == dir || folder.startsWith(dir + '/')) && dir.length() > length) {
                best = entry.value
                length = dir.length()
            }
        }
        return best
    }

    private long sessionStart(List<Proc> procs) {
        long first = procs[0].jvmStart
        try {
            long created = Files.readAttributes(sessionDir, BasicFileAttributes).creationTime().toMillis()
            return created < first && first - created < MAX_PREP_MS ? created : first
        } catch (Exception ignored) {
            return first
        }
    }

    // one group per run of a suite; a suite that ran more than once is named (1), (2) in plan order
    private List<Suite> group(List<Proc> procs) {
        Map<String, Suite> suites = new LinkedHashMap<>()
        for (Proc p : procs) {
            String key = p.group ?: p.suite
            Suite s = suites.get(key)
            if (s == null) {
                s = new Suite(id: p.suite, key: key)
                suites.put(key, s)
            }
            s.procs << p
        }
        List<Suite> out = new ArrayList<Suite>(suites.values())
        List<String> order = new ArrayList<String>(planRuns.keySet())
        out.sort { Suite a, Suite b ->
            int ia = order.indexOf(a.key), ib = order.indexOf(b.key)
            ia >= 0 && ib >= 0 ? ia <=> ib : a.start() <=> b.start()
        }
        Map<String, Integer> seen = [:]
        for (Suite s : out) {
            int count = out.count { Suite o -> o.id == s.id } as int
            int nth = (seen.get(s.id) ?: 0) + 1
            seen.put(s.id, nth)
            s.label = name(s.id) + (count > 1 ? ' (' + nth + ')' : '')
            for (Proc p : s.procs) {
                p.suiteLabel = s.label
            }
        }
        return out
    }

    private Obs observe(Suite suite, long start, long end) {
        List<Proc> testProcs = suite.testProcs()
        boolean parallel = suite.parallel()
        int k = 1
        if (parallel) {
            Integer configured = suite.procs.collect { Proc p -> p.max }.find { it != null }
            k = configured != null ? configured : peak(testProcs)
        }
        long testSpan = testProcs.empty ? 0L :
                testProcs.collect { Proc p -> p.end() }.max() - testProcs.collect { Proc p -> p.jvmStart }.min()
        return new Obs(
                session: sessionDir.fileName.toString(),
                suite: suite.id,
                key: parallel ? 'parallel:' + k : 'off',
                k: k,
                at: end,
                elapsed: end - start,
                before: suite.start() - start,
                startup: median(testProcs.collect { Proc p -> p.startup() }),
                shutdown: median(testProcs.collect { Proc p -> p.shutdown() }),
                fixed: end - start - testSpan,
                tests: suite.runs().collect { Run r -> r.duration() })
    }

    private static int peak(List<Proc> procs) {
        List<long[]> edges = []
        for (Proc p : procs) {
            edges << ([p.jvmStart, 1L] as long[])
            edges << ([p.end(), -1L] as long[])
        }
        edges.sort { long[] a, long[] b -> a[0] <=> b[0] ?: a[1] <=> b[1] }
        int running = 0
        int max = 0
        for (long[] edge : edges) {
            running += (int) edge[1]
            max = Math.max(max, running)
        }
        return Math.max(1, max)
    }

    // waits at most LOCK_WAIT_MS once per report, so a stuck lock can never hold up Katalon
    private Object withLock(Closure body) {
        if (lockTimedOut) {
            throw new IOException('Run Clock history is locked by another process')
        }
        Files.createDirectories(historyFile.parent)
        FileChannel channel = FileChannel.open(historyFile.resolveSibling('history.lock'),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        try {
            FileLock lock = null
            long deadline = System.currentTimeMillis() + LOCK_WAIT_MS
            while (lock == null) {
                try {
                    lock = channel.tryLock()
                } catch (OverlappingFileLockException ignored) {
                }
                if (lock == null) {
                    if (System.currentTimeMillis() > deadline) {
                        lockTimedOut = true
                        throw new IOException('Run Clock history is locked by another process')
                    }
                    Thread.sleep(50L)
                }
            }
            try {
                return body.call()
            } finally {
                lock.release()
            }
        } finally {
            channel.close()
        }
    }

    private List<Obs> updateHistory(Obs cur) {
        return (List<Obs>) withLock {
            List<Obs> all = readHistory()
            all.removeAll { Obs o -> o.session == cur.session && o.suite == cur.suite }
            all << cur
            all = trim(all, settings.historyLimit)
            writeAtomic(historyFile, all.collect { Obs o -> JsonOutput.toJson(o.toMap()) }.join('\n') + '\n')
            return all.findAll { Obs o -> o.suite == cur.suite }
        }
    }

    // the newest perSuite observations of every suite, and no more than HISTORY_CAP in all
    private static List<Obs> trim(List<Obs> all, int perSuite) {
        Map<String, List<Obs>> bySuite = new LinkedHashMap<>()
        for (Obs o : all) {
            List<Obs> list = bySuite.get(o.suite)
            if (list == null) {
                list = []
                bySuite.put(o.suite, list)
            }
            list << o
        }
        List<Obs> kept = []
        for (List<Obs> list : bySuite.values()) {
            list.sort { Obs o -> o.at }
            kept.addAll(list.size() > perSuite ? list.subList(list.size() - perSuite, list.size()) : list)
        }
        kept.sort { Obs o -> o.at }
        return kept.size() > HISTORY_CAP ? new ArrayList<Obs>(kept.subList(kept.size() - HISTORY_CAP, kept.size())) : kept
    }

    // call only while holding the lock
    private List<Map> updateRuns(Map<String, Object> entry) {
        Path runsFile = historyFile.resolveSibling('runs.jsonl')
        List<Map> runs = []
        if (Files.exists(runsFile)) {
            for (String line : Files.readAllLines(runsFile, StandardCharsets.UTF_8)) {
                try {
                    Map run = (Map) json.parseText(line)
                    if (run.session != entry.session && (run.ci || Files.isDirectory(sessionPage(run).parent.parent))) {
                        runs << run
                    }
                } catch (Exception ignored) {
                }
            }
        }
        runs << entry
        runs.sort { Map r -> num(r, 'at') }
        if (runs.size() > HISTORY_LIMIT) {
            runs = new ArrayList<Map>(runs.subList(runs.size() - HISTORY_LIMIT, runs.size()))
        }
        writeAtomic(runsFile, runs.collect { Map r -> JsonOutput.toJson(r) }.join('\n') + '\n')
        return runs
    }

    private static Map<String, Object> runEntry(Map<String, Object> model) {
        Map stats = (Map) model.stats
        List<Map> runs = (List<Map>) ((List<Map>) model.procs).collectMany { Map p -> (List<Map>) p.runs }
        Map mode = (Map) model.mode
        String setting = model.kind == 'suite' ?
                (mode.parallel ? 'Parallel, max ' + mode.max : 'Parallel off') :
                (model.plan ? (String) ((Map) model.plan).setting :
                        'Collection, ' + ((List) model.suites).size() + (((List) model.suites).size() == 1 ? ' suite' : ' suites'))
        return [
                session  : model.session,
                kind     : model.kind,
                title    : model.title,
                entity   : model.entity ?: model.title,
                setting  : setting,
                start    : model.start,
                at       : model.end,
                'final'  : model.get('final'),
                elapsed  : stats.elapsed,
                before   : stats.before,
                startup  : stats.startup,
                tests    : stats.tests,
                runs     : stats.runs,
                passed   : runs.count { Map r -> r.status == 'PASSED' },
                failed   : runs.count { Map r -> r.status == 'FAILED' || r.status == 'ERROR' || r.status == 'INCOMPLETE' },
                processes: stats.processes,
                peak     : stats.peak,
                fastest  : model.advice ? ((Map) model.advice).fastest : null,
                key      : model.plan ? ((Map) model.plan).key : null,
                count    : model.kind == 'collection' ? ((List) model.suites).size() : null,
                complete : model.complete
        ] as Map<String, Object>
    }

    private List<Obs> readHistory() {
        List<Obs> all = []
        if (!Files.exists(historyFile)) {
            return all
        }
        for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
            try {
                all << Obs.fromMap((Map) json.parseText(line))
            } catch (Exception ignored) {
            }
        }
        return all
    }

    // a measured setting is the median of its newest SAMPLE runs; estimates use the newest run's parameters
    private static List<Option> options(Obs cur, List<Obs> history) {
        Map<String, Obs> latest = [:]
        Map<String, List<Obs>> byKey = [:]
        List<Obs> all = history.findAll { Obs o -> !(o.session == cur.session && o.suite == cur.suite) }
        all << cur
        for (Obs o : all) {
            Obs seen = latest.get(o.key)
            if (seen == null || seen.at < o.at || o.is(cur)) {
                latest.put(o.key, o)
            }
            List<Obs> list = byKey.get(o.key)
            if (list == null) {
                list = []
                byKey.put(o.key, list)
            }
            list << o
        }
        Map<String, List<Obs>> samples = [:]
        for (Map.Entry<String, List<Obs>> entry : byKey.entrySet()) {
            List<Obs> list = entry.value.toSorted { Obs o -> -o.at }
            samples.put(entry.key, list.size() > SAMPLE ? list.subList(0, SAMPLE) : list)
        }
        int n = cur.tests.size()
        Set<Integer> ks = new TreeSet<Integer>([2, 4, 8].findAll { Integer k -> k <= n })
        for (Obs o : latest.values()) {
            if (o.key != 'off') {
                ks << o.k
            }
        }
        List<Option> out = [option('off', 1, latest, samples, cur)]
        for (Integer k : ks) {
            out << option('parallel:' + k, k, latest, samples, cur)
        }
        return out
    }

    private static Option option(String key, int k, Map<String, Obs> latest, Map<String, List<Obs>> samples, Obs cur) {
        List<Obs> measured = samples.get(key)
        if (measured) {
            return new Option(key: key, k: k, millis: middle(measured.collect { Obs o -> o.elapsed }), runs: measured.size(),
                    measured: true, current: key == cur.key)
        }
        long millis
        Obs par = nearestParallel(latest, k)
        if (key == 'off') {
            millis = cur.before + par.startup + sum(cur.tests) + par.shutdown
        } else if (par != null) {
            millis = par.fixed + makespan(cur.tests, k, par.startup + par.shutdown)
        } else {
            Obs off = latest.get('off')
            long overhead = off.startup + off.shutdown
            millis = off.before + 2 * overhead + makespan(cur.tests, k, overhead)
        }
        return new Option(key: key, k: k, millis: millis)
    }

    private static Obs nearestParallel(Map<String, Obs> latest, int k) {
        Obs best = null
        for (Obs o : latest.values()) {
            if (o.key == 'off') {
                continue
            }
            if (best == null || Math.abs(o.k - k) < Math.abs(best.k - k) || (Math.abs(o.k - k) == Math.abs(best.k - k) && o.k > best.k)) {
                best = o
            }
        }
        return best
    }

    private static long makespan(List<Long> tests, int k, long overhead) {
        List<Long> sorted = tests.toSorted().reverse()
        long[] slots = new long[Math.max(1, Math.min(k, sorted.size()))]
        long max = 0L
        for (int i = 0; i < sorted.size(); i++) {
            int min = 0
            for (int j = 1; j < slots.length; j++) {
                if (slots[j] < slots[min]) {
                    min = j
                }
            }
            slots[min] = slots[min] + sorted.get(i).longValue() + overhead
            max = Math.max(max, slots[min])
        }
        return max
    }

    private String single(Suite suite, Obs cur, List<Option> options, Map<String, Object> advice, Map<String, Object> slow) {
        List<Proc> testProcs = suite.testProcs()
        List<Run> runs = suite.runs()
        int listenerOnly = suite.procs.size() - testProcs.size()
        String mode = suite.parallel() ?
                'Parallel, Max Concurrent Instances ' + cur.k + ', peak ' + peak(testProcs) + ' at once' :
                'Parallel off, 1 process'
        StringBuilder out = new StringBuilder()
        out << 'Run Clock | ' + name(suite.id) + ' | ' + cur.session + '\n'
        out << mode + '\n\n'
        out << row('Elapsed', secs(cur.elapsed), 'run start to last process exit, without Run Clock itself')
        out << row('Before first process', secs(cur.before), 'Katalon preparing the run')
        out << row('Processes', String.valueOf(suite.procs.size()),
                testProcs.size() + ' with tests' + (listenerOnly ? ', ' + listenerOnly + ' listener-only' : ''))
        out << row('Start-up', secs(medianOrNull(testProcs.collect { Proc p -> p.startup() })),
                'median, process start to first listener call')
        out << row('Shutdown', secs(medianOrNull(testProcs.collect { Proc p -> p.shutdown() })),
                'median, last listener call to process exit')
        out << row('Tests', secs(sum(cur.tests)),
                runs.size() + (runs.size() == 1 ? ' run' : ' runs') + (runs ? ', longest ' + secs(cur.tests.max()) : ''))
        long own = 0L
        for (Proc p : suite.procs) {
            own += p.own
        }
        if (own > 0) {
            out << row('Run Clock itself', secs(own), 'writing this report, left out of every time here')
        }
        if (slow != null) {
            out << row('Slower than usual', '+' + slow.percent + '%', 'median ' + secs(num(slow, 'median')) + ' over the last ' + slow.runs + ' runs with this setting')
        }
        if (options) {
            out << '\n' << pad('Setting', 22) << padLeft('Time', 12) << '\n'
            for (Option o : options) {
                out << pad(label(o), 22) << padLeft((o.measured ? '' : '~') + secs(o.millis), 12) << '   ' << note(o) << '\n'
            }
            out << '\n' << adviceText(advice) << '\n'
        }
        return out.toString().trim()
    }

    private static String note(Option o) {
        if (!o.measured) {
            return 'estimate'
        }
        if (o.runs > 1) {
            return 'median of ' + o.runs + ' runs' + (o.current ? ', this setting' : '')
        }
        return o.current ? 'this run' : 'measured once'
    }

    private static Map<String, Object> advice(List<Option> options, Obs cur) {
        Option best = options.min { Option o -> o.millis }
        Option measured = options.findAll { Option o -> o.measured }.min { Option o -> o.millis }
        boolean maybe = !best.measured && best.millis < measured.millis * 0.9
        return [
                fastest       : label(measured),
                key           : measured.key,
                setting       : setting(measured),
                action        : action(measured),
                currentFastest: measured.current,
                delta         : cur.elapsed - measured.millis,
                maybe         : maybe ? [label: label(best), millis: best.millis, setting: setting(best), action: action(best)] : null
        ] as Map<String, Object>
    }

    private static String adviceText(Map<String, Object> advice) {
        Map maybe = (Map) advice.maybe
        if (maybe == null) {
            return 'Fastest: ' + advice.fastest + ' (' + advice.setting + ').'
        }
        return 'Fastest measured: ' + advice.fastest + '. ' + maybe.label + ' may be faster (~' + secs((Long) maybe.millis) +
                '); run once with ' + maybe.setting + ' to measure it.'
    }

    // the part of Run Clock's own report writing that pushed the end of the run back
    private static long ownOnClock(List<Suite> suites, List<Proc> procs, long end) {
        boolean together = false
        for (int i = 0; i < suites.size(); i++) {
            for (int j = i + 1; j < suites.size(); j++) {
                if (suites[i].start() < suites[j].end() && suites[j].start() < suites[i].end()) {
                    together = true
                }
            }
        }
        long total = 0L
        long last = 0L
        for (Proc p : procs) {
            total += p.own
            last = Math.max(last, p.end() - p.own)
        }
        return together ? Math.max(0L, end - last) : total
    }

    private static Map<String, Object> stats(List<Proc> procs, long start, long end, long ownClock) {
        List<Proc> testProcs = procs.findAll { Proc p -> !p.runs.empty }
        List<Long> durations = (List<Long>) procs.collectMany { Proc p -> p.runs.collect { Run r -> r.duration() } }
        long own = 0L
        for (Proc p : procs) {
            own += p.own
        }
        return [
                elapsed     : end - start - ownClock,
                wall        : end - start,
                before      : procs.collect { Proc p -> p.jvmStart }.min() - start,
                processes   : procs.size(),
                withTests   : testProcs.size(),
                listenerOnly: procs.size() - testProcs.size(),
                startup     : medianOrNull(testProcs.collect { Proc p -> p.startup() }),
                shutdown    : medianOrNull(testProcs.collect { Proc p -> p.shutdown() }),
                tests       : sum(durations),
                runs        : durations.size(),
                longest     : durations ? durations.max() : null,
                peak        : peak(testProcs),
                own         : own
        ] as Map<String, Object>
    }

    private static Map<String, Object> procModel(Proc p) {
        return [
                pid      : p.pid,
                suite    : p.suite,
                suiteName: p.suiteLabel ?: name(p.suite),
                group    : p.group ?: p.suite,
                label    : processLabel(p),
                jvmStart : p.jvmStart,
                first    : p.first,
                last     : p.last,
                exit     : p.exit,
                own      : p.own,
                runs     : p.runs.collect { Run r -> [id: r.id, name: name(r.id), status: r.status, start: r.start, end: r.end] }
        ] as Map<String, Object>
    }

    private String collection(List<Suite> suites, long start, long end, Map<String, Object> plan) {
        StringBuilder out = new StringBuilder()
        out << 'Run Clock | ' + name(planRoot ?: sessionDir.fileName.toString()) + ' | ' + sessionDir.fileName + '\n\n'
        out << row('Elapsed', secs(end - start), 'run start to last process exit, without Run Clock itself')
        out << row('Before first process', secs(suites.collect { Suite s -> s.start() }.min() - start), 'Katalon preparing the run')
        out << '\n' << pad('Suite', 30) << padLeft('Starts', 12) << padLeft('Ends', 12) << padLeft('Elapsed', 12) <<
                padLeft('Processes', 11) << padLeft('Tests', 7) << '\n'
        for (Suite s : suites) {
            out << pad(s.label ?: name(s.id), 30) << padLeft(secs(s.start() - start), 12) << padLeft(secs(s.start() - start + s.span()), 12) <<
                    padLeft(secs(s.span()), 12) << padLeft(String.valueOf(s.procs.size()), 11) <<
                    padLeft(String.valueOf(s.runs().size()), 7) << '\n'
        }
        if (plan != null) {
            out << '\n' << pad('Collection setting', 26) << padLeft('Time', 12) << '\n'
            for (Map o : (List<Map>) plan.options) {
                out << pad((String) o.label, 26) << padLeft((o.measured ? '' : '~') + secs(num(o, 'millis')), 12) << '   ' <<
                        (!o.measured ? 'estimate' : (num(o, 'runs') > 1 ? 'median of ' + o.runs + ' runs' + (o.current ? ', this setting' : '') :
                                (o.current ? 'this run' : 'measured once'))) << '\n'
            }
            if (plan.advice) {
                out << '\n' << plan.advice << '\n'
            }
        }
        return out.toString().trim()
    }

    private String html(Map<String, Object> model) {
        Path template = projectDir.resolve('Include').resolve('config').resolve('runclock').resolve('report.html')
        if (!Files.exists(template)) {
            return '<!DOCTYPE html><meta charset="utf-8"><title>Run Clock</title><pre>' + esc((String) (model.summary ?: '')) + '</pre>'
        }
        String json = JsonOutput.toJson(model).replace('<', '\\u003c')
        return new String(Files.readAllBytes(template), StandardCharsets.UTF_8).replace('__RUN_CLOCK_DATA__', json)
    }

    private static String processLabel(Proc p) {
        if (p.runs.size() == 1) {
            return name(p.runs[0].id)
        }
        if (p.runs.size() > 1) {
            return p.runs.size() + ' tests'
        }
        if (p.before && !p.after) {
            return 'before-suite listeners'
        }
        if (p.after && !p.before) {
            return 'after-suite listeners'
        }
        return 'listeners'
    }

    private static String label(Option o) {
        label(o.key, o.k)
    }

    private static String label(String key, int k) {
        key == 'off' ? 'Parallel off' : 'Parallel, max ' + k
    }

    private static String setting(Option o) {
        o.key == 'off' ? 'Enable Parallel Execution unticked' :
                'Enable Parallel Execution ticked, Max Concurrent Instances ' + o.k
    }

    private static String action(Option o) {
        o.key == 'off' ? "Untick Enable Parallel Execution in this suite's Execution Information." :
                'Tick Enable Parallel Execution and set Max Concurrent Instances to ' + o.k + '.'
    }

    private static String row(String label, String value, String note) {
        pad(label, 22) + padLeft(value, 12) + '   ' + note + '\n'
    }

    private static String name(String id) {
        id == null ? '' : id.substring(id.lastIndexOf('/') + 1)
    }

    private static String secs(Long ms) {
        if (ms == null) {
            return '-'
        }
        if (Math.abs(ms) < 120000L) {
            return String.format(Locale.ROOT, '%.1f s', ms / 1000d)
        }
        return String.format(Locale.ROOT, '%d min %02d s', ms.intdiv(60000L), (ms % 60000L).intdiv(1000L))
    }

    private static String pad(String s, int width) {
        s.length() >= width ? s.substring(0, width - 1) + ' ' : s.padRight(width)
    }

    private static String padLeft(String s, int width) {
        s.padLeft(width)
    }

    private static String esc(String s) {
        s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
    }

    private static long sum(List<Long> values) {
        long total = 0
        for (Long v : values) {
            total += v
        }
        return total
    }

    private static long median(List<Long> values) {
        Long m = medianOrNull(values)
        return m != null ? m : 0L
    }

    // median with the two middle values averaged, for comparing runs
    private static long middle(List<Long> values) {
        List<Long> sorted = values.toSorted()
        int n = sorted.size()
        if (n == 0) {
            return 0L
        }
        int mid = n.intdiv(2) as int
        return n % 2 == 1 ? sorted[mid] : ((sorted[mid - 1] + sorted[mid]).intdiv(2) as long)
    }

    private static Long medianOrNull(List<Long> values) {
        List<Long> known = values.findAll { Long v -> v != null }.toSorted()
        return known ? known[known.size().intdiv(2) as int] : null
    }

    static long num(Map m, String key) {
        Object v = m.get(key)
        return v instanceof Number ? ((Number) v).longValue() : 0L
    }

    private static void writeAtomic(Path target, String content) {
        Files.createDirectories(target.parent)
        Path tmp = target.resolveSibling(target.fileName.toString() + '.' + ProcessHandle.current().pid() + '.tmp')
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8))
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (Exception ignored) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
