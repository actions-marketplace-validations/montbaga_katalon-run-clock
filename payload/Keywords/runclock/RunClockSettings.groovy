package runclock

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// Include/config/runclock/runclock.properties, each value overridable by an environment variable for CI
class RunClockSettings {

    static final List<String> CI_VARS = ['CI', 'JENKINS_URL', 'TF_BUILD', 'GITLAB_CI', 'GITHUB_ACTIONS', 'TEAMCITY_VERSION', 'BITBUCKET_BUILD_NUMBER']

    String openReport = 'studio'
    String history = 'on'
    Path historyDir
    int historyLimit = 50
    int slowerBy = 25
    String webhookUrl = ''
    boolean ciSummary = true
    Map<String, String> env = [:]

    static RunClockSettings load(Path projectDir, Map<String, String> env) {
        Properties props = new Properties()
        Path file = projectDir.resolve('Include').resolve('config').resolve('runclock').resolve('runclock.properties')
        try {
            if (Files.exists(file)) {
                Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)
                try {
                    props.load(reader)
                } finally {
                    reader.close()
                }
            }
        } catch (Throwable ignored) {
        }
        RunClockSettings s = new RunClockSettings(env: env ?: [:])
        s.openReport = pick(props, s.env, 'openReport', 'RUN_CLOCK_OPEN', ['studio', 'always', 'never']) ?: s.openReport
        s.history = pick(props, s.env, 'history', 'RUN_CLOCK_HISTORY', ['on', 'readonly', 'off']) ?: s.history
        s.ciSummary = (pick(props, s.env, 'ciSummary', 'RUN_CLOCK_CI_SUMMARY', ['on', 'off']) ?: 'on') == 'on'
        Path dir = projectDir.resolve('Reports').resolve('run-clock')
        for (String v : values(props, s.env, 'historyDir', 'RUN_CLOCK_HISTORY_DIR')) {
            try {
                Path p = Paths.get(v)
                dir = p.isAbsolute() ? p : projectDir.resolve(v)
                break
            } catch (Throwable ignored) {
            }
        }
        s.historyDir = dir.toAbsolutePath().normalize()
        s.historyLimit = number(props, s.env, 'historyLimit', 'RUN_CLOCK_HISTORY_LIMIT', 50, 5, 1000)
        s.slowerBy = number(props, s.env, 'slowerBy', 'RUN_CLOCK_SLOWER_BY', 25, 5, 500)
        List<String> urls = values(props, s.env, 'webhookUrl', 'RUN_CLOCK_WEBHOOK')
        s.webhookUrl = urls ? urls[0] : ''
        return s
    }

    boolean onCi() {
        return CI_VARS.any { String name -> env.get(name) != null }
    }

    // the environment variable first, then the file; a value that is not valid is skipped
    private static List<String> values(Properties props, Map<String, String> env, String key, String envName) {
        List<String> out = []
        String fromEnv = env.get(envName)
        if (fromEnv != null && fromEnv.trim()) {
            out << fromEnv.trim()
        }
        String fromFile = props.getProperty(key)
        if (fromFile != null && fromFile.trim()) {
            out << fromFile.trim()
        }
        return out
    }

    private static String pick(Properties props, Map<String, String> env, String key, String envName, List<String> allowed) {
        for (String v : values(props, env, key, envName)) {
            if (v.toLowerCase() in allowed) {
                return v.toLowerCase()
            }
        }
        return null
    }

    private static int number(Properties props, Map<String, String> env, String key, String envName, int fallback, int min, int max) {
        for (String v : values(props, env, key, envName)) {
            try {
                int n = Integer.parseInt(v)
                return n < min ? min : (n > max ? max : n)
            } catch (Exception ignored) {
            }
        }
        return fallback
    }
}
