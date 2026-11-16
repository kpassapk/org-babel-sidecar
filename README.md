# emacs-sidecar

A console beside your Emacs for org notebooks with executable blocks, and
a way to write those notebooks together with a sandboxed agent.

One babashka process attaches to the Emacs you are running, through
[podless-emacs](https://github.com/kpassapk/podless-emacs), and does two
things:

- **A console** ([charm.clj](https://github.com/kpassapk/charm.clj)): the
  org files of a directory, the outline of one notebook, every block run
  with its output, what went wrong, and the agents attached.
- **An agent on a buffer**: a container with no credentials reads the
  buffer's text and each run's output, and asks for edits. Emacs applies
  them, marks what the agent wrote, and asks you before a block it wrote
  or changed is run.

It is the successor of [devops.bb](https://github.com/kpassapk/devops.bb).
Nothing goes in your init file: the Emacs side is loaded over the
connection, and its hooks are removed when the sidecar exits.

```
      servers, cloud accounts
              ▲
              │ blocks you read and run
 ┌────────────┴────────────────┐          ┌──────────────────────────┐
 │ Emacs                       │          │ sandbox: the agent       │
 │   notebook buffer ──────────┼─ text ──▶│ /feed (read-only)        │
 │   runs, saves, edits ───────┼─ events ▶│   sidecar follow --wake  │
 │                             │          │   sidecar output RUN     │
 │   one undo step, marked ◀───┼─ edits ──│ /outbox                  │
 │   asked before it runs      │          │   sidecar append/replace │
 └──────────┬──────────────────┘          └──────────────────────────┘
            │ podless                       no route to Emacs, the host
 ┌──────────┴──────────────────┐            or the servers
 │ sidecar: console, feed      │
 └─────────────────────────────┘
```

## Requirements

- babashka 1.13.226 or later
- Emacs 29 or later, running a server (`M-x server-start`, or a daemon)
- A checkout of podless-emacs beside this one (`../podless-emacs`); it is
  not published yet
- For agents: podman or docker, and `CLAUDE_CODE_OAUTH_TOKEN` (from
  `claude setup-token`) or `ANTHROPIC_API_KEY` in the sidecar's environment

It works on any org file with source blocks. With
[devops.el](https://github.com/kpassapk/devops.el) the console also shows
each block's `#+TARGET`.

## Install

```
ln -s ~/src/github.com/kpassapk/emacs-sidecar/bin/sidecar ~/.local/bin/
sidecar image          # once, for agents: builds emacs-sidecar-sandbox
```

## The console

```
sidecar console [--dir D] [--server S] [--no-box]
```

`--dir` is the directory whose org files are indexed (default: here);
`--server` the Emacs server's name or socket (default `server`).

```
 emacs-sidecar · emacs ● 32.0.50 · org 9.8.7 · ~/ops                                     ▶1  ✗1  @1  11:23:05
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 1 Index  2 files                       ┃  2 Notebook  host-facts.org · Host facts · unsaved · following
◉* Host facts  :o… ▶1 ✗1 @host-facts 2▸ ┃  * TODO Facts                                                  :web:
   scratch                              ┃    ** OS
                                        ┃ ▌    ▸ sh         os                             web  ▶ running 7.0s
                                        ┃  * Checks
                                        ┃    ▸ sh         exit 3                                      ✗ exit 3
                                        ┃ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
                                        ┃  3 Runs  3 · 1 running
                                        ┃ 11:22:58 ▶ host-facts.org:6       os  sh                running 7.0s
                                        ┃ 11:21:00 ✗ host-facts.org:10      Checks  sh                  exit 3
                                        ┃ 11:20:00 ✓ host-facts.org:6       os  sh                        3.4s
                                        ┃ ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ ┃  4 Problems  1
 5 Agents  1                            ┃ 11:21:00 ✗ run     host-facts.org:10  cannot reach db
● host-facts  host-f… behind 2  edits 3 ┃
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 ⏎ output  e emacs                                                                   tab pane  ? help  q quit
```

| Pane | Shows |
|---|---|
| **Index** | the org files under `--dir`, newest first, and notebooks open in Emacs from elsewhere. A denote name gives the title and tags; `#+TITLE` and `#+FILETAGS` win. `◉` you are in it, `●` open, `*` unsaved, `▶` running, `✗` blocks whose last run failed, `@` its agent |
| **Notebook** | one file's headings and blocks, from the buffer's text when it has unsaved edits. Each block has its last run; `▌` is where your point is |
| **Runs** | every block run, newest first: sync, async, and the blocks a `:var` reference ran (`↳`) |
| **Problems** | failed runs, Emacs errors and warnings in notebooks, blocks the gate refused, edits refused as unsafe, agents that stopped |
| **Agents** | each attachment: its state, how many events the agent has not acked, edits applied and refused |

Under 100 columns only the pane with the keyboard is shown.

| Key | Does |
|---|---|
| `tab` `1`–`5` | move between panes |
| `j` `k` `↑` `↓`, `space` `PgUp`, `g` `G` | move, page, first and last |
| `⏎` | index: show the notebook; notebook: go there in Emacs; runs, problems, agents: detail |
| `e` | show it in Emacs |
| `/` | filter the index |
| `f` | the notebook pane follows where you are in Emacs (on at start) |
| `a` | attach an agent to the notebook |
| `s` `d` | agents: open the agent's terminal in a tmux window (or say the command); detach |
| `r` `?` `q` | read again; help; quit |

A run's detail has the code as it was sent, stderr, and that run's
output. Quitting detaches every agent and removes its container.

`sidecar events [--json] [--count N]` prints the same events, a line
each, without the console.

## Working with an agent

1. `a` on a notebook. The sidecar starts a container named
   `sidecar-NAME`; the mode line of the buffer says `agent:NAME`.
2. `s` on the agent (or `podman exec -it sidecar-NAME claude`), and say
   what you want. The image has the `sidecar-notebook` skill, which
   teaches the loop.
3. The agent's text appears in the buffer with a bar beside it. Each of
   its edits is one undo step.
4. Run a block it wrote with `C-c C-c`. Emacs shows the block and asks
   first. The agent is woken with the run and reads its output.
5. `M-x sidecar-accept` (block, region or text at point) or
   `sidecar-accept-buffer` takes the bar away without running;
   `sidecar-next-agent-text` moves to the next marked text.

Without a container (`--no-box`, or `sidecar attach FILE --no-box`), the
feed and outbox directories are shown instead, for whatever mounts them.
`sidecar box …` runs the agent's commands on the host with
`SIDECAR_FEED` and `SIDECAR_OUTBOX` set, which is how the tests drive it.

### What the agent can and cannot do

The container gets two mounts and the Claude credential, nothing else.

| | |
|---|---|
| reads | the buffer's text, unsaved edits included; events; each run's status, output and stderr (64 KB each, head and tail kept) |
| writes | edits: `append` under a heading, or `replace` of text that occurs exactly once. An edit that no longer fits the buffer is refused, and the agent reads again |
| cannot | run a block, reach Emacs, the host or a server, or write a file on the host. No workbook directory is shared, so there is nothing to plant in one |

Emacs refuses, at the moment of the edit, text it would evaluate before
you are asked anything:

- Emacs Lisp in header arguments: `#+begin_src`, `#+header:`,
  `#+property:`, `#+call:`, `:header-args:` and inline blocks and calls,
  and in `#+TBLFM:`;
- a `Local Variables:` line, or `-*-` on the file's first lines;
- `#+SETUPFILE:`.

Before a run, the gate asks when the block, or the block a `#+call:`
names, has text the agent wrote or changed, a deletion included. A block
with noweb references asks while the buffer holds any agent text you have
not accepted. Your own blocks are never asked about. The marks survive a
revert, and stay after a detach until you accept them.

The outbox is read as data: small JSON files with plain names, regular
files only, never followed, loaded or opened in Emacs.

### Limits

- The container needs network for the Claude API, and nothing limits
  where else it connects. Output is not redacted: the skill tells the
  agent to treat it as data and not to repeat secrets.
- Text of the agent's that you kill and yank elsewhere loses its mark.
- `[[elisp:…]]` and `[[shell:…]]` links the agent writes are not refused;
  Org asks before following them.
- One podless listener serves a server-socket directory. Two Emacsen
  that share one (the default on macOS) cannot both be attached.

## What is in your Emacs

`resources/sidecar/sidecar.el`, loaded by the sidecar. While a sidecar is
connected it advises `org-babel-execute-src-block` and a few functions
around it, and adds hooks for saves, reverts, kills, first changes and
your position. When the last sidecar disconnects, those are removed and
attached buffers are detached.

What stays for the life of the Emacs: the gate's advice on
`org-babel-execute-src-block`, which does nothing in a buffer without
agent text; the mode-line entry; and the `sidecar-accept` commands. cljbang
and podless's own listener stay too, as podless-emacs documents.

## Commands

| Command | Does |
|---|---|
| `sidecar console [--dir D] [--server S] [--no-box]` | the console |
| `sidecar events [--server S] [--json] [--count N]` | a line per event |
| `sidecar attach FILE [--name N] [--no-box] [--server S]` | attach an agent without the console, until stopped |
| `sidecar image` | build the sandbox image (`SIDECAR_ENGINE`, `SIDECAR_IMAGE`) |
| `sidecar box …` | the agent's commands, on the host |

In the sandbox, `sidecar` is the agent's:

| Command | Answers |
|---|---|
| `sidecar status` | the feed, the notebook's path, `attached`, the cursor, `behind` |
| `sidecar notebook` | the path of the notebook's text |
| `sidecar follow [--wake [MS]] [--once \| --count N] [--json] [--after SEQ]` | a line per event after the cursor; `--wake` batches, and prints only what needs action |
| `sidecar ack SEQ` | events up to SEQ are handled |
| `sidecar output RUN [--json]` | one run's status, stderr and output |
| `sidecar append [--under 'A / B'] [TEXT]` | add TEXT, or stdin, at the end of that subtree or of the notebook |
| `sidecar replace --old S\|--old-file F [--new S\|--new-file F]` | replace the one occurrence; the new text may come on stdin |

The files and events are described in [doc/protocol.md](doc/protocol.md).

## Development

| Namespace | Does |
|---|---|
| `sidecar.emacs` | the podless link: attach, load sidecar.el, events in, calls out, reconnect |
| `sidecar.model` | events → state; pure |
| `sidecar.org` | org text → headings and blocks; which headings a change touched |
| `sidecar.index` | the org files of a directory |
| `sidecar.view` | state → screen text; pure |
| `sidecar.console` | the charm.clj loop: keys, timers, commands |
| `sidecar.agent` | attachments: buffer → feed, outbox → buffer |
| `sidecar.feed` | the feed's and outbox's files, for both sides |
| `sidecar.sandbox` | the container |
| `sidecar.box` | the agent's commands |

```
bb test            # all suites
bb test view feed  # some
```

The `emacs` and `agent` suites start an `emacs --batch -Q` of their own
and never touch an Emacs you are running. The view tests compare whole
screens as text.
