# A sandbox for an agent attached to a notebook: Claude Code and the
# `sidecar` command, and no credentials.
#
# Build from the checkout's root (`sidecar image` does this):
#
#   podman build -f sandbox/Dockerfile -t emacs-sidecar-sandbox .
#
# The sidecar starts one per attached buffer, with two mounts and nothing
# else of the host's:
#
#   /feed    read-only: the notebook's text, events, each run's output
#   /outbox  read-write: the edits the agent asks for, and its cursor
#
# Nothing here can reach the host's Emacs or its servers: no ssh keys, no
# cloud CLIs or profiles, no git credentials.  Claude Code needs network
# for the API, and a credential the sidecar passes from its own
# environment: CLAUDE_CODE_OAUTH_TOKEN (from `claude setup-token`) or
# ANTHROPIC_API_KEY.

FROM babashka/babashka:1.13.226

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      ca-certificates git nodejs npm jq ripgrep less \
 && rm -rf /var/lib/apt/lists/*

RUN npm install -g @anthropic-ai/claude-code

# The agent's user is not root, and owns neither /feed nor the image.
RUN (userdel -r ubuntu 2>/dev/null || true) \
 && useradd -m -u 1000 -s /bin/bash claude \
 && mkdir -p /feed /outbox /work \
 && chown claude:claude /outbox /work

# Only the agent's side of the sidecar: two namespaces, no dependencies.
COPY src/sidecar/box.clj src/sidecar/feed.clj /opt/sidecar/src/sidecar/
COPY sandbox/bin/sidecar /usr/local/bin/sidecar

COPY --chown=claude:claude sandbox/skills /home/claude/.claude/skills

# npm's prefix is root's: the updater would fail on every screen.
ENV DISABLE_AUTOUPDATER=1

# Skip the theme picker and onboarding; the login comes from the env.
# Trust /work up front: the trust prompt hangs `exec -it`.
RUN echo '{"hasCompletedOnboarding":true,"theme":"dark","projects":{"/work":{"hasTrustDialogAccepted":true}}}' \
      > /home/claude/.claude.json \
 && chown claude:claude /home/claude/.claude.json

USER claude
ENV SIDECAR_FEED=/feed SIDECAR_OUTBOX=/outbox
WORKDIR /work

# Check the command loads, so a broken image fails at build.
RUN sidecar 2>&1 | grep -q usage

# Warm Claude Code's feature-flag cache before anyone attaches: on a
# fresh container the first session is offered no Monitor tool, which
# the skill's loop uses.  Seen on Claude Code 2.1.304 (devops.bb).
CMD ["sh", "-c", "claude -p ok >/dev/null 2>&1; exec sleep infinity"]
