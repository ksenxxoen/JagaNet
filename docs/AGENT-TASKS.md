# Agent tasks

How another agent (Grok Bot) or a person hands tasks to Claude, and how the result comes back.
GitHub Issues are the task queue; labels are the task status. Nothing polls: the
[Claude workflow](../.github/workflows/claude.yml) starts on GitHub events.

## Statuses

| Label | Meaning | Set by |
|---|---|---|
| `claude:todo` | New task, waiting for Claude. Adding it starts a run | Grok Bot |
| `claude:in-progress` | Claude is working on it | workflow |
| `claude:done` | Work is finished, a pull request waits for review | workflow |
| `claude:failed` | The run failed; the issue has a link to the log | workflow |

The issue is closed when the pull request (`Closes #N`) is merged.

## Flow

1. **Grok Bot creates the task**: an issue with the `claude:todo` label, using the
   [Agent task](../.github/ISSUE_TEMPLATE/agent-task.yml) form or the same sections in the body:
   `Goal`, `Acceptance criteria` (a checklist), `Context`.
2. **Claude works**: label becomes `claude:in-progress`. Claude makes a branch, commits, opens
   a pull request with `Closes #N`, and leaves a short report on the issue.
3. **Label becomes `claude:done`.** Grok Bot reviews the pull request against the acceptance
   criteria:
   - accepted: merge the pull request; the issue closes itself;
   - changes needed: comment `@claude <what to fix>` on the pull request or the issue. The
     task goes back to `claude:in-progress` and Claude pushes to the same pull request.
4. **`claude:failed`**: read the linked log, then re-add `claude:todo` or comment `@claude`.

## Rules for Claude

- One task — one pull request. Don't widen the scope beyond the acceptance criteria; mention
  extra findings in the report instead.
- Build and test what you changed (`./gradlew :server:test`, `./gradlew :shared:allTests`, …)
  before pushing; say in the report what was run.
- If the task is unclear, don't guess: ask in an issue comment and stop. Grok Bot answers with
  an `@claude` comment.
- Report format: what was done, what was checked, what is left or doubtful.

## Setup (once)

1. Repository secret `CLAUDE_CODE_OAUTH_TOKEN` (run `claude setup-token`) or `ANTHROPIC_API_KEY`:
   Settings → Secrets and variables → Actions.
2. Install the Claude GitHub App on the repository: <https://github.com/apps/claude>.
3. Grok Bot needs a GitHub token with write access to issues and pull requests (a fine-grained
   personal access token for this repository is enough).
   - If it acts as a person's account, that account needs write access to the repository.
   - If it acts as a GitHub App, put its bot login (for example `grok-bot[bot]`) in the
     repository variable `CLAUDE_ALLOWED_BOTS`.
   Events made with the workflow's own `GITHUB_TOKEN` don't start workflows, so Grok Bot must
   use its own token.
4. The labels are created by the workflow on its first run; create `claude:todo` by hand before
   the first task (or let the issue form add it).

## Example for Grok Bot (GitHub REST API)

```bash
curl -X POST https://api.github.com/repos/ksenxxoen/JagaNet/issues \
  -H "Authorization: Bearer $GROK_GITHUB_TOKEN" \
  -d '{
    "title": "[task] Add rate limit to /auth/code",
    "labels": ["claude:todo"],
    "body": "## Goal\n...\n\n## Acceptance criteria\n- [ ] ...\n\n## Context\n..."
  }'
```

Waiting for the result: subscribe to repository webhooks (`issues`, `pull_request`) and react to
the `claude:done` / `claude:failed` labels, or check issues with those labels from time to time.
