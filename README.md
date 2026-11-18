# File Organizer Agent (Java · Spring Boot · LangGraph4j)

A tool-calling agent that sorts the files of a folder into category sub-directories
(finance, health, travel, …) based on each file's **content**.
The model works only through tools (`list_files`, `read_file`, `create_directory`, `move_file`),
and **every change to the filesystem is approved by a human first**.
See [PLAN.md](PLAN.md) for the design, the graph and the guardrails.

## Run

```bash
mvn package -DskipTests
java -jar target/file-organizer-agent-1.0.0.jar                                   # organizes ./demo-inbox
java -jar target/file-organizer-agent-1.0.0.jar --organizer.target-folder=/path/to/folder
java -jar target/file-organizer-agent-1.0.0.jar --organizer.generate-demo-files=true  # write 12 demo files first
```

`--organizer.generate-demo-files=true` is a setup step outside the agent: it writes 12 demo
files into the target folder (existing files are never overwritten) before the agent runs.

## Approvals

When the model asks for changes, the agent first lists them, then asks:

```
=== The agent wants to make 14 changes ===
   1. Move dinner.txt -> cooking/dinner.txt
   2. Move bill.txt -> finance/bill.txt  (overwrites finance/bill.txt)
   ...
=== Approval required (move_file) - change 1 of 14 ===
  Move dinner.txt -> cooking/dinner.txt
Approve? [y] this change / [b] the rest of this batch / [t] all move_file changes / [a] all changes, overwrites included / [n] no:
Overwrite finance/bill.txt? [y/N]:
```

| Answer | Effect |
|---|---|
| `y` | Only this change. |
| `b` | This change and the rest of this batch. Overwrites are still asked. |
| `t` | This change and all later changes of the same tool. Overwrites are still asked. |
| `a` | This change and all later changes of every tool, **overwrites included**, without asking again. |
| `n` / Enter / no console | Rejected. |

When a change would replace an existing file and `a` was not chosen, a second question asks
whether to overwrite. Without a "yes" the existing file is kept and the move is skipped.
An overwritten file is never lost: it is moved to the backup directory first (see Undo).
Read-only tools (`list_files`, `read_file`) never ask, and the graph does not even pause for them.

## Undo

```bash
java -jar target/file-organizer-agent-1.0.0.jar --organizer.target-folder=/path --organizer.undo-last-run=true
```

Every change is recorded in a journal (`data/journal/`). The undo shows the steps, asks for
confirmation, then moves files back, restores overwritten files from `data/backups/` and removes
the directories the run created (only when empty). It never overwrites anything; conflicting steps are
skipped and reported. Only the last run of a folder can be undone.

## One run per folder

A folder can be organized by only one run at a time (an OS file lock in `data/locks/`, released
automatically if the process dies). A second run on the same folder exits with code `2`.

## Use an LLM

```bash
export ANTHROPIC_API_KEY=sk-ant-...
java -jar target/file-organizer-agent-1.0.0.jar --organizer.llm.enabled=true
```

Without an LLM (the default), an offline keyword model calls the same tools, so the agent,
the approvals and the tests work offline. With `organizer.llm.enabled=true` an API key is required:
the application does not start without it.

What the model gets:
- Instructions only in the system prompt. Folder data (names, contents) only inside escaped
  `<file_list>` / `<file_content>` / `<binary_file>` tags, declared as untrusted data.
- Each file's content **once**: after the next model call it is replaced by a short note, and the model
  writes its decision per file (`- trip.txt => travel`). This keeps cost flat as folders grow.
- No content of binary files (images, PDFs, ...), only name and size.

## Limits

| Setting | Default | Purpose |
|---|---|---|
| `organizer.llm.timeout` | `60s` | Maximum time of one model call |
| `organizer.llm.max-retries` | `2` | Retries of a failed model call |
| `organizer.llm.max-tokens` | `4096` | Maximum length of one model answer |
| `organizer.agent.max-model-calls` | `50` | The run stops after this many model calls |
| `organizer.agent.max-tokens-per-run` | `500000` | The run stops when the model has used this many tokens |
| `organizer.max-files` | `100` | `list_files` returns at most this many files |
| `organizer.max-content-characters` | `4000` | `read_file` returns at most this many characters |

A failing model or a reached limit stops the run and is shown in the report, together with the
model usage (calls and tokens) and notes such as files left out by `max-files`.
Invalid values (e.g. `max-files=0`) stop the application at startup with a clear message.

## Crash recovery (SQLite checkpoints)

The graph state is saved in SQLite (`./data/organizer-checkpoints.db`) after every step.
Each folder has one run (`organize:<absolute folder path>`). If the application stops in the middle
(crash, `kill -9`, power loss), the next start on the **same folder** shows what the unfinished run had
done and asks whether to continue it (`y`) or discard it and start over (`n`, the default).

- Earlier `t` / `a` approvals are not carried over a restart; you are asked again.
- Changes made before the crash stay; moves that were already done are not repeated.
- Finished runs are not resumed. Their checkpoints (which contain file excerpts) are deleted;
  only a history row (run, time, error flag) stays in `LG4JThreadTag`.
- A run that ends with an error is marked finished and not resumed.

| Setting | Default |
|---|---|
| `organizer.checkpoints.enabled` | `true` (`false` = memory only, no recovery) |
| `organizer.checkpoints.database` | `./data/organizer-checkpoints.db` |
| `organizer.data-directory` | `./data` (locks, journals, backups) |

The data directory and the database are readable by the current user only (`700` / `600`).

## Logging

- **Console**: INFO and above (the main steps of a run).
- **File** `logs/file-organizer-agent.log`: everything from DEBUG up, so you can follow every step
  (model calls, tool calls and their results, human answers).
  Written through an async appender. A new file starts every day or at 10 MB; old files are
  compressed and kept for 14 days.

Configuration: `src/main/resources/logback-spring.xml`; log folder: `--organizer.logging.directory=/path`.

## Test

```bash
mvn test
```
