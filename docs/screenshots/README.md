# Screenshots

Real runs of Run Clock 1.0.0 in Katalon Studio 11.6 Enterprise on Windows 11, 5 November 2026, numbered in the order they were made. Each image is the report Run Clock wrote for that run, captured once every animation had finished. Run Clock's own report-writing time is left out of every time shown.

The test project has a suite of 4 short API tests (Run Clock API), a suite of 2 API tests (API Test Suite), and two Test Suite Collections. The Run Clock API tests fail because the project has no real API key; their timings are still real.

## Part 1: one suite, every parallel setting

Each suite was run once with each setting under Test Suite > Execution Information. Max 4 was run three times.

| # | Suite | Setting | Real time | Report |
|---|---|---|---|---|
| 1 | Run Clock API | Parallel off | 6.6 s | [01](part-1-suites/01-run-clock-api-parallel-off.jpg) |
| 2 | Run Clock API | Parallel, max 1 | 37.0 s | [02](part-1-suites/02-run-clock-api-max-1.jpg) |
| 3 | Run Clock API | Parallel, max 2 | 21.0 s | [03](part-1-suites/03-run-clock-api-max-2.jpg) |
| 4 | Run Clock API | Parallel, max 3 | 65.8 s | [04](part-1-suites/04-run-clock-api-max-3.jpg) |
| 5 | Run Clock API | Parallel, max 4 | 28.4 s | [05](part-1-suites/05-run-clock-api-max-4-run-1.jpg) |
| 6 | Run Clock API | Parallel, max 8 | 17.3 s | [06](part-1-suites/06-run-clock-api-max-8.jpg) |
| 7 | Run Clock API | Parallel, max 4 | 26.5 s | [07](part-1-suites/07-run-clock-api-max-4-run-2.jpg) |
| 8 | Run Clock API | Parallel, max 4 | 27.2 s | [08](part-1-suites/08-run-clock-api-max-4-run-3.jpg) |
| 9 | API Test Suite | Parallel off | 28.7 s | [09](part-1-suites/09-api-test-suite-parallel-off.jpg) |
| 10 | API Test Suite | Parallel, max 2 | 47.9 s | [10](part-1-suites/10-api-test-suite-max-2.jpg) |

What they show:

- For short API tests, parallel execution is slower. Run Clock API took 6.6 s with parallel off and 17.3 s at best with it on, because each test case starts its own Java process.
- The first report has one measured setting and estimates for the others, marked `~`. Each later run replaces an estimate with a measurement.
- Report 8 shows max 4 as the median of its three runs (27.2 s), so one slow or fast run does not change the advice.

## Part 2: Test Suite Collections

Each collection was run with Execution mode Sequential, then Parallel with different Max concurrent instances.

| # | Collection | Setting | Real time | Report |
|---|---|---|---|---|
| 11 | New Test Suite Collection, 2 suites | Sequential | 94.7 s | [11](part-2-collections/11-new-tsc-sequential.jpg) |
| 12 | New Test Suite Collection, 2 suites | Parallel, max 2 | 68.3 s | [12](part-2-collections/12-new-tsc-parallel-max-2.jpg) |
| 13 | kzissues, 5 suites | Sequential | 190.7 s | [13](part-2-collections/13-kzissues-sequential.jpg) |
| 14 | kzissues, 5 suites | Parallel, max 2 | 153.6 s | [14](part-2-collections/14-kzissues-parallel-max-2.jpg) |
| 15 | kzissues, 5 suites | Parallel, max 4 | 140.6 s | [15](part-2-collections/15-kzissues-parallel-max-4.jpg) |
| 16 | kzissues, 5 suites | Parallel, max 8 (all 5 at once) | 91.1 s | [16](part-2-collections/16-kzissues-parallel-max-8.jpg) |
| 19 | kzissues, 5 suites | Parallel, max 8, after the one-write change | 130.6 s | [19](part-2-collections/19-kzissues-parallel-max-8-one-write.jpg) |

What they show:

- After the sequential runs (11, 13), Run Clock recommended running the suites at the same time. Doing so saved 26 s on the 2-suite collection and 100 s on kzissues.
- Each report gives every suite's real start and end on the collection's clock, and the slowest tests across all suites.
- Settings already run show their measured time; the rest are estimates, marked `~`.
- Run 19 was made after a change so that only the suite that finishes last writes the report. Run Clock's own writing fell from 24.3 s in run 16 to 2.6 s. Its real time is higher because the machine was slower that time: Java start-up took 31.3 s against 13.9 s, and the slowest test 86.5 s against 61.6 s.

## Part 3: what Run Clock remembers

The `history` setting in `runclock.properties`, each value tried once on Run Clock API with Parallel, max 4.

| # | history | Real time | Report |
|---|---|---|---|
| 17 | readonly | 29.9 s | [17](part-3-settings/17-run-clock-api-history-readonly.jpg) |
| 18 | off | 63.4 s | [18](part-3-settings/18-run-clock-api-history-off.jpg) |

What they show:

- With `readonly`, the report compares the run with earlier ones as usual (max 4 shows the median of 4 runs, this one included) and says at the bottom that history is read only. The run is not added to the All runs page or to later comparisons.
- With `off`, the report stands alone: no Over time chart, no links to other runs, and the other settings are estimates. This run was slow, 63.4 s against a usual 27 s, and with no history Run Clock cannot say so.

## Part 4: for CI pipelines

| # | Test | Real time | Image |
|---|---|---|---|
| 20 | Run Clock API, Parallel, max 4, with `webhookUrl` set | 64.5 s | [report](part-4-ci/20-run-clock-api-webhook-slower-than-usual.jpg), [message](part-4-ci/20-webhook-message.jpg) |
| 21 | Run Clock API, Parallel, max 4, from the command line with `katalonc` | 27.6 s | [report](part-4-ci/21-run-clock-api-command-line.jpg) |

What they show:

- With `webhookUrl` set, Run Clock posts one line per run to a Slack-compatible incoming webhook. For this test the address was a small receiver on the same PC, so the message image is that receiver's page, not Slack. The message was posted once.
- The run took 64.5 s, against a usual 27.2 s for max 4, so the report, the Log Viewer and the message all say it was 137% slower than usual. The usual time is the median of the three max 4 runs with history on; runs 17 and 18 are not part of it.
- Run 21 ran in Katalon's console mode, as on a CI agent. No browser opened, the run was added to the All runs page, and Run Clock wrote the pipeline summary `Reports/run-clock/ci-summary.md`. This is it, as a CI job summary page shows it:

> ### Run Clock: Run Clock API
>
> | | |
> |---|---|
> | Real time | 27.6 s |
> | Setting | Parallel, max 4 |
> | Start-up per process | 10.1 s |
> | Tests | 4 runs, 13.6 s |
>
> **Fastest setting:** Parallel off. Untick Enable Parallel Execution in this suite's Execution Information.

## Close-ups

Eleven of the reports, one image per section at double resolution, in `sections/`:

| Folder | Why it is here |
|---|---|
| [01-run-clock-api-parallel-off](sections/01-run-clock-api-parallel-off) | A first run: one setting measured, the others estimated |
| [08-run-clock-api-max-4-run-3](sections/08-run-clock-api-max-4-run-3) | Every setting measured, max 4 as a median of three runs, the suite over time |
| [10-api-test-suite-max-2](sections/10-api-test-suite-max-2) | Parallel slower than off for a second suite |
| [12-new-tsc-parallel-max-2](sections/12-new-tsc-parallel-max-2) | A collection after it was switched to parallel |
| [13-kzissues-sequential](sections/13-kzissues-sequential) | A collection's first run, with advice to run suites at once |
| [16-kzissues-parallel-max-8](sections/16-kzissues-parallel-max-8) | Every way the collection was run, side by side |
| [17-run-clock-api-history-readonly](sections/17-run-clock-api-history-readonly) | History read only: compared with earlier runs, not recorded; see the footer |
| [18-run-clock-api-history-off](sections/18-run-clock-api-history-off) | History off: no Over time, estimates only; see the footer |
| [19-kzissues-parallel-max-8-one-write](sections/19-kzissues-parallel-max-8-one-write) | Only the last suite writes the report: Run Clock itself in `time` and `timeline` |
| [20-run-clock-api-webhook-slower-than-usual](sections/20-run-clock-api-webhook-slower-than-usual) | Slower than usual, in red under the title in `top` |
| [21-run-clock-api-command-line](sections/21-run-clock-api-command-line) | A command-line run, back to its usual time |

Each folder has these sections, in page order:

| File | Section |
|---|---|
| `top` | The run on the dial: real time, setting, replay and scrubber |
| `figures` | The run in figures |
| `settings` | For a suite, every setting raced. For a collection, each suite on the collection's clock |
| `collection` | The collection's own Execution mode, raced (collections only) |
| `over-time` | Real time of the recent runs of the same suite or collection (not with history off) |
| `time` | Where the process time went |
| `tests` | The slowest tests, and tests slower than usual |
| `timeline` | Second by second: every process, grouped by suite |
| `numbers` | Every number as a table, and the text Katalon's Log Viewer shows |
| `footer` | Footer |

## All runs page

`Reports/run-clock/index.html`, written after every run. Captured after run 21.

| Image | What it shows |
|---|---|
| [01](all-runs/01-all-runs-as-it-opens.jpg) | As it opens: the latest run (21, from the command line) against the run before it (20), and the suite's 10 runs |
| [02](all-runs/02-all-runs-every-suite.jpg) | Filtered to every suite: 19 runs. Runs 17 and 18 are not listed, because history was read only or off |
| [03](all-runs/03-all-runs-kzissues.jpg) | Filtered to kzissues |
| [04](all-runs/04-all-runs-new-test-suite-collection.jpg) | Filtered to New Test Suite Collection |
| [05](all-runs/05-all-runs-api-test-suite.jpg) | Filtered to API Test Suite |
| [06](all-runs/06-compare-kzissues-sequential-vs-max-8.jpg) | Two runs ticked and compared: kzissues sequential against its latest max 8 run |
| [07](all-runs/07-all-runs-phone.jpg) | The All runs page on a phone |
| [08](all-runs/08-kzissues-max-8-phone.jpg) | A collection report on a phone |
