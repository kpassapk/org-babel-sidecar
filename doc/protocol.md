# Protocol

Two hops: Emacs to the sidecar, over podless, and the sidecar to a
sandboxed agent, over two directories.

## Emacs → sidecar

`sidecar.el` publishes on the podless topic `sidecar`. Every event is a
map with `:event` and `:time`. Only org-mode buffers that visit a file
publish; `:file` is the file's truename.

| `:event` | When | Fields |
|---|---|---|
| `run` | a block runs | `:phase`, `:status`, `:id`, `:file`, `:line`, `:name`, `:lang`, `:outline`, `:parent` |
| `modify` | a buffer's first change since it was unmodified | `:line`, `:outline` |
| `save`, `revert`, `open`, `kill` | the buffer is saved, reloaded, visited, killed | `:modified` |
| `focus` | the selected window's point moved to another line of a notebook, once Emacs is idle | `:line`, `:outline` |
| `text` | an attached buffer changed, 0.3 s after the first change | `:text`, `:tick`, `:causes`, `:modified` |
| `gate` | the gate decided, or text was accepted | `:decision`, `:line`, `:reason` |
| `problem` | a warning was displayed, or a command signalled in a notebook | `:kind`, `:message`, `:source` |

A run has three phases:

- `start`, with `:body`, the block's code as written. `:status` is
  `running`.
- `async`, when the block returned and its result is still to come
  (ob-comint's async sessions, which devops.el uses for every targeted
  block).
- `end`, with `:status` (`done`, `error`, `cancelled`), `:ms`, and
  `:output`: what stands under `#+RESULTS`, without Org's `: ` and
  `#+begin_example`, or the block's value when nothing was inserted.
  `:exit` and `:stderr` are there when the language reported them through
  `org-babel-eval-error-notify`; a non-zero exit makes the status
  `error`. A block that signals ends with `error` and `:error`.

An async run ends when its session prints the end marker ob-comint typed
after the block. Emacs looks at the sessions of the runs still waiting
once a second:

- A session at its prompt for `sidecar-async-stall-seconds` (5), with
  nothing running and the end marker not printed, gets the marker again,
  once, and the run ends as usual. A command of the block read the
  session's input and took the marker (`podman compose run`, `ssh`,
  `read`); a `problem` says so. A remote session is only seen at its
  prompt, not whether something runs there.
- A session that was killed, or whose process ended, ends the run with
  `error`, `:error`, and `:output`: what it printed until then.

A block sent to a terminal with devops.el's `devops-run-in-terminal` is
a run too, with `start` and `end` and no `async`. Its end has
`:terminal`, `:exit` when the shell told it, and as `:output` what the
terminal showed: the command as typed, then what it printed. Nothing
lands under `#+RESULTS`. The gate asks as for any run. The end comes
when the shell says the command is over (OSC 133, ghostel's shell
integration). Without that, a local terminal ends the run when nothing
runs in it any more, and a remote one only when it is closed.

`:id` is 12 hex digits, made by Emacs per run. `:parent` is the id of the
run that caused this one, for a block run to resolve a `:var` reference.
`:line` is the `#+begin_src` line when the block ran.

`:causes` of a `text` event says who changed the buffer since the last
one: any of `operator`, `run` (a result was inserted) and `agent` (an
edit was applied).

`:decision` is `approved` or `declined` (you were asked), `refused` (the
block has Emacs Lisp in its header arguments) or `accepted`
(`sidecar-accept`).

The sidecar calls these functions, with data for arguments:

| Function | Does |
|---|---|
| `sidecar-install CONN-ID` | start publishing; returns versions |
| `sidecar-buffers` | the notebooks open |
| `sidecar-text FILE` | the buffer's text, `:tick`, `:modified`; nil if not open |
| `sidecar-visit FILE [LINE]` | show it in the selected window |
| `sidecar-attach FILE NAME`, `sidecar-detach FILE` | start and stop `text` events and edits |
| `sidecar-apply-edit FILE EDIT` | apply one edit; returns `:line`, or signals with the reason |

The sidecar adds events of its own for the console: `emacs` (`up`,
`down`), `buffers`, `agent` and `edit`.

## Sidecar → agent

```
FEED/                    the sidecar writes; mounted read-only at /feed
  status.json            {feed, notebook, attached, epoch, heartbeat, seq}
  events.jsonl           one event per line, in seq order
  events.prev.jsonl      the feed before this one
  outputs/<run>.json     {status, exit, ms, output, stderr, error, input, name, line, outline}
  notebook/<file>        the buffer's text
  replies/<edit>.json    {ok, line} or {ok: false, error}

OUTBOX/                  the agent writes; mounted read-write at /outbox
  edits/<edit>.json      {op: "append", text, under: [..]}
                         {op: "replace", old, new}
  cursor.json            {epoch, seq}
```

The feed holds the attached notebook's `run`, `save`, `revert`, `kill`
and `gate` events as above, without code or output and with `file` as a
file name, plus:

| `event` | When | Fields |
|---|---|---|
| `change` | the buffer's text changed; the notebook file is written first | `causes`, `headings`: the outline paths whose own text changed, `modified` |
| `edit` | an edit from the outbox was answered | `id`, `op`, `ok`, `line`, `error` |

Every event has `seq`, from 1, and `epoch`, the time the feed was
opened. An attach opens a new feed: the old `events.jsonl` becomes
`events.prev.jsonl`, and outputs, replies and the notebook start empty.

### Delivery

- `follow` prints the events after the cursor and never moves it. Only
  `ack` does, and never backwards, so delivery is at least once: a
  `follow` that ends and starts again gives whatever was not acked.
- When the feed's epoch is not the cursor's, `follow` gives the rest of
  the old feed first, under a notice, then says the feed restarted and
  goes on from seq 1. An ack in the new feed passes the old one.
- `--wake` waits until 750 ms pass without an event (at most 3 s from
  the first), then prints the batch without what needs nothing: a run
  that only started, a change with no `operator` among its causes, an
  `edit`, an `approved` or `accepted` gate. When the batch's last event
  is one of those, `! ack N` ends it.
- The sidecar rewrites `status.json` every 3 s. `follow` says when
  `heartbeat` has not changed for 15 s, by its own clock: the two clocks
  are never compared.
- The sidecar reads `cursor.json` to show how far behind the agent is.

### Edits

The agent's command writes one file into `edits/`, by rename, and waits
for the reply of the same name. The sidecar polls every 300 ms, takes
each regular file of at most 1 MB with a plain name, removes it, has
Emacs apply it, and answers. Emacs applies an edit in one request, which
podless makes one undo step.

- `replace` finds `old` exactly once, and writes only what differs
  between `old` and `new`.
- `append` goes to the end of the subtree `under` names, or of the
  buffer, after one blank line.
- What was written is marked. A block the edit touched is marked whole.
- Nothing is saved: saving stays yours.
