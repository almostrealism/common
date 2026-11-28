# GitHub Webhooks

The controller can receive GitHub webhook deliveries and announce pull request
activity in the Slack channel of the workstream that owns the pull request's
branch. A workstream's channel then shows, within seconds, when its pull
request merges, closes without merging, or changes draft status, without
anyone going to GitHub to find out.

See [Slack Integration](./slack-integration.md) for the controller's other
endpoints and for how workstreams map to channels.

## What is announced

| Event | Action | Message |
|-------|--------|---------|
| `pull_request` | `closed`, merged | `PR #N (title) merged into `base` by user` |
| `pull_request` | `closed`, not merged | `PR #N (title) closed without merging by user` |
| `pull_request` | `converted_to_draft` | `PR #N (title) converted to draft by user` |
| `pull_request` | `ready_for_review` | `PR #N (title) marked ready for review by user` |

`PR #N` links to the pull request. Every other delivery is acknowledged and
ignored.

The mapping from event to message lives in one place,
`io.flowtree.github.GitHubWebhookEvent`: a map from `event.action` to the
function that renders the message. A new announcement (for example
`pull_request_review.submitted`) is one entry in that map. An event whose
payload has no `pull_request` object, such as `check_suite`, also needs its
branch and repository read in `GitHubWebhookEvent.headBranch()` and
`headRepoUrl()`.

## Routing a delivery to a workstream

A delivery reaches the workstream whose `defaultBranch` is the pull request's
head branch (`pull_request.head.ref`) **and** whose `repoUrl` names the head
repository (`pull_request.head.repo.clone_url`). The two URLs are compared by
repository identity, so a workstream registered with
`git@github.com:owner/repo.git` matches the HTTPS clone URL GitHub sends. A
workstream with no `repoUrl` never matches. A pull request from a fork names the
fork as its head repository, so it never reaches a workstream that only shares
the branch name.

A delivery that matches no workstream is ignored and logged at debug level
only. A webhook on a repository sees every pull request, and most of them
belong to no workstream.

## Verification and redelivery

- **Signature.** Every delivery must carry an `X-Hub-Signature-256` header
  that matches the HMAC-SHA256 of the raw request body, keyed with the
  configured secret. An unsigned or badly signed delivery is answered `401` and
  not acted on. Until a secret is configured, every delivery is answered `503`.
- **Deduplication.** GitHub redelivers a delivery under the same
  `X-GitHub-Delivery` identifier, both on its own and when someone presses
  *Redeliver*. The controller remembers each identifier it handled for 24 hours
  and answers a repeat with `200` and `"outcome":"duplicate"` without posting
  again. A delivery whose post failed (`500`) or whose body was not JSON
  (`400`) is forgotten, so a redelivery is acted on.
- **Ping.** The `ping` GitHub sends when the webhook is created is verified
  and answered with `"outcome":"pong"`. A green ping in GitHub's *Recent
  Deliveries* list confirms the URL and the secret.

The response body is `{"ok": <bool>, "outcome": "<outcome>"}`, where the outcome
is one of `posted`, `ignored`, `pong`, `duplicate`, `bad_signature`,
`not_configured`, `malformed` or `failed`.

## Setup

1. **Choose a secret** — a long random string, e.g. `openssl rand -hex 32`.

2. **Configure the controller.** Add the top-level key to the controller's
   workstreams YAML and restart the controller (the key is read at startup):

   ```yaml
   githubWebhookSecret: "<the secret>"
   ```

3. **Expose the endpoint.** GitHub must be able to reach
   `POST /api/github/webhook` on the controller's API port (default `7780`),
   for example through a reverse proxy or tunnel that terminates TLS. Only
   that path needs to be public.

4. **Create the webhook** on each repository (or the organization) under
   *Settings → Webhooks → Add webhook*:

   | Field | Value |
   |-------|-------|
   | Payload URL | `https://<public controller host>/api/github/webhook` |
   | Content type | `application/json` (the form-encoded type is not supported) |
   | Secret | the secret from step 1 |
   | SSL verification | enabled |
   | Events | *Let me select individual events* → **Pull requests** |

5. **Check the ping** under the webhook's *Recent Deliveries*. A `503` means
   the controller has no secret configured. A `401` means the secrets differ.

## Related

- `POST /api/pr-events` (`PullRequestEventHandler`) is a separate route. Its
  caller reports a terminal pull request event, and the controller cancels the
  workstream's in-flight jobs. The webhook does not cancel jobs.
