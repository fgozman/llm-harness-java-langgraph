# File Organizer Agent – Plan

## Goal
A Spring Boot application that runs a LangGraph (LangGraph4j) tool-calling agent which
sorts the files of a folder into category directories by their content:
1. The model lists the folder (`list_files`).
2. It reads the files (`read_file`) and decides a category/domain for each.
3. It creates the missing category directories (`create_directory`).
4. It moves every file into its category directory (`move_file`).
5. A human readable report is printed.

**Every filesystem change must be approved by a human** (human in the loop).
The model only *requests* tool calls; changing calls run only after approval.

Demo files are **not** part of the agent. They are written by a separate setup step
(`DemoFileGenerator`) before the agent starts, only when `organizer.generate-demo-files=true`.

## Technology
| Concern              | Choice                                           |
|----------------------|--------------------------------------------------|
| Language / build     | Java 21, Maven                                   |
| Application frame    | Spring Boot 3.5 (non-web, `CommandLineRunner`)   |
| Agent graph          | LangGraph4j `langgraph4j-core` 1.9.x             |
| Model                | LangChain4j Anthropic (`ChatModel` with tools), or an offline keyword model |
| Checkpoints          | LangGraph4j `langgraph4j-sqlite-saver` (`SQLiteSaverV2`), or in memory |
| Logging              | Logback, async rolling file + console            |
| Threads              | Virtual threads: graph loop and nodes (`emitterExecutor`), model HTTP client, Spring executors |
| Tests                | JUnit 5, AssertJ (Spring Boot starter test)      |

## Agent graph

```
START ──► call_model ──(tool calls)──► ⏸? execute_tools ─┐
              ▲                                           │
              └───────────────────────────────────────────┘
          call_model ──(no tool calls / stopped)──► report ──► END
```

⏸? = `ExecuteToolsNode` implements LangGraph4j's `InterruptibleAction`: it pauses only when a call
would change files and has no human decision yet. Batches that only read run without a pause.

### Checkpoints and crash recovery
- The state is saved after every step in SQLite (`SQLiteSaverV2`, `CheckpointStore`).
- Run id = `organize:<absolute folder path>`, so a restart on the same folder finds an unfinished run.
- On start, `OrganizerAgent` checks for an unfinished run: if found, it asks the human whether to
  continue it (standing approvals are cleared, pending changes are asked again) or discard it.
- At END the run is released (`releaseThread(true)`) and its checkpoint rows are deleted.
- A run that fails with an error is released as failed, so it is not resumed into the same error.

### Human in the loop
1. `call_model` stores the model's answer (with its tool calls) in the state.
2. The graph pauses at `execute_tools` if the batch contains changes without a decision.
3. `OrganizerAgent` passes the pending calls to `ToolCallReviewer`, which asks the
   `HumanApprover` (console) for every call that would change the filesystem:
   - Several changes are first shown as a numbered list.
   - `y` this change, `b` rest of the batch, `t` all changes of this tool, `a` all changes
     (overwrites included), `n` no.
   - If the change overwrites a file and `a` was not chosen, a separate overwrite question follows.
   - Read-only calls, invalid calls and calls that change nothing are not asked.
4. The decisions and the standing approvals (`ApprovalScope`) are written into the state
   (`graph.updateState`) and the graph resumes.
5. `execute_tools` runs read-only calls directly and changing calls only with an approving decision
   (missing decision = rejected). Every call gets a result message for the model.
6. No answer (e.g. no interactive console) means *rejected* – safe by default.

State (`OrganizerState`) keys:
- `targetFolder`                                  – input
- `messages`                                      – conversation (task, model answers, tool results);
                                                    replaced as a whole so file contents can be compacted
- `modelCalls`, `inputTokens`, `outputTokens`     – usage so far (limits, report)
- `toolDecisions`                                 – decisions for the pending tool calls
- `approvalScope`                                 – standing approvals ("this tool", "all changes")
- `appliedChanges`, `rejectedChanges`, `failedChanges` – appended results
- `notices`                                       – appended notes for the human (e.g. max-files)
- `stopReason`                                    – why the run stopped early, if it did
- `report`                                        – final summary text

## Guardrails
- **Human approval** for every change, per change / per tool / all; separate overwrite approval.
- **Restricted tools**: only list, read, create directory, move. No delete, no free paths.
- **Path safety** (`SafePaths`): only plain names directly inside the folder; `..`, `/`, hidden
  names and symbolic links are refused. Directory names are sanitized.
- **Re-check before execution**: tools validate again when executing; an overwrite that appeared
  after approval is refused.
- **Prompt separation**: instructions only in the system prompt; folder data (file names, contents)
  only inside escaped `<file_list>` / `<file_content>` / `<binary_file>` tags, declared as untrusted
  data. Control characters in names are encoded, so a name cannot add lines.
- **Safe console output**: names and model text are printed through `DisplayText` (no terminal escapes).
- **Limits**: model timeout and retries, max model calls and max tokens per run, max files listed,
  max characters read per file, max tokens per answer. Settings are validated at startup.
- **Small context**: each file content is sent once (`CallModelNode.withoutFileContents`); binary files never.
- **One run per folder**: OS file lock (`FolderLocks`).
- **Undo**: change journal + backups of overwritten files (`ChangeJournal`, `UndoService`).
- **Private data**: data directory `700`, database `600`.
- **Safe failure**: a failing model or a reached limit stops the run and is reported.

## Package layout (`com.example.organizer`)
```
OrganizerApplication               Spring Boot entry point (exits when the agent is done)
OrganizerRunner                    CommandLineRunner: optional demo setup, then the agent (or the undo)
DemoFileGenerator                  writes the demo files (setup, outside the agent)
DisplayText                        safe console output
config/
  OrganizerProperties              typed configuration (folder, limits, checkpoints, llm)
  OrganizerConfiguration           beans: Anthropic or offline model, console human approval
agent/
  OrganizerGraphFactory            the graph: nodes, edges, checkpoints
  OrganizerAgent                   drives a run: pause -> ask the human -> resume; crash recovery
  OrganizerState, ConversationMessage   the graph state and the conversation stored in it
  CallModelNode, ExecuteToolsNode, ReportNode   the graph steps
  AgentInstructions                system prompt and task
  CheckpointStore                  SQLite or in-memory checkpoint saver + cleanup of finished runs
approval/
  HumanApprover / ConsoleHumanApprover   y / b / t / a / n, overwrite question
  ToolCallReviewer                 approval rules, ApprovalScope, ToolDecision
tools/
  FileTool, ToolRegistry           tool interface; checks and runs tool calls
  ListFilesTool, ReadFileTool,
  CreateDirectoryTool, MoveFileTool
  ToolCall, ToolPreview, ToolOutcome, ToolArguments
  SafePaths, UntrustedText         path safety and directory names, data escaping
journal/
  ChangeJournal, UndoService       change journal, backups, undo of the last run
  FolderLocks                      one run per folder
  DataFiles                        owner-only access, file names per folder
model/
  OfflineKeywordChatModel          deterministic ChatModel that calls the tools (no LLM needed)
  KeywordContentCategorizer        its keyword rules
```
