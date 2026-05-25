# GitHub App Credentials for FlowTree Workspaces

Status: Proposal — not yet started
Owner: TBD

## TL;DR

Today a FlowTree workstream authenticates to GitHub with a **personal access
token (PAT)** declared in the top-level `githubOrgs` map of `workstreams.yaml`.
A PAT authenticates as a single user and can read/write **every** repository
that user can see — there is no way to scope it to one repo, and it does not
expire on its own.

This proposal adds a second credential mechanism: **GitHub App installation
tokens**, attached to a workspace the same way `githubOrg` is selected today.
The controller holds an App private key, and at job-dispatch time it mints a
short-lived (≈1 hour) installation token **scoped to only the repositories that
workstream is allowed to touch**. That token is used both for the agent's git
clone/push and for the GitHub API proxy.

This unlocks three things FlowTree needs across use-cases:

1. **Per-repo isolation** — a job can be handed credentials that reach exactly
   one repo, not the whole org.
2. **Short-lived credentials** — installation tokens expire automatically; no
   long-lived secret sits in an agent container.
3. **Automated provisioning** — an App with `Administration: write` can create
   repositories (e.g. from a template) as part of an automated onboarding flow.

`githubOrgs` (PAT) stays supported. A workstream picks one mechanism.

## 1. Current state

- `workstreams.yaml` has a top-level `githubOrgs:` map: `org -> { token: <PAT> }`.
- A workstream selects one via the optional `githubOrg:` field.
- `GitHubTokenValidator`
  (`flowtree/runtime/src/main/java/io/flowtree/slack/GitHubTokenValidator.java`)
  validates each PAT at controller startup (`GET /user`, `GET /repos/{owner}/{repo}`).
- Agents do not hold the PAT directly. GitHub **API** calls are proxied:
  agent → ar-manager → controller (`FlowTreeApiEndpoint` GitHub proxy) → GitHub,
  using the org's PAT.
- Git **transport** (clone/push) is handled in
  `flowtree/base/src/main/java/io/flowtree/jobs/GitOperations.java` and
  `flowtree/runtime/src/main/java/io/flowtree/jobs/GitRepositorySetup.java`,
  driven by the workstream's `repoUrl` / `defaultBranch`.

The gap: one PAT per org means one identity with org-wide reach, no per-repo
scoping, and no expiry.

## 2. What a GitHub App gives us

A GitHub App is an org-installed identity that authenticates in two steps:

1. **App JWT** — signed with the App's private key, proves "I am this App."
2. **Installation token** — `POST /app/installations/{installationId}/access_tokens`
   exchanges the JWT for a token that acts as the installation. Two key
   properties:
   - The request body may include `repositories` / `repository_ids` to
     **down-scope the token to specific repos** (when the App is installed on
     more than those).
   - The returned token **expires in ~1 hour**.

So the controller can mint, per job, a token that can reach only the repo(s)
that workstream owns and that dies on its own shortly after.

The same App, if granted `Administration: write` on the org, can also create
repositories (`POST /orgs/{org}/repos`, or `POST /repos/{template}/generate`).

## 3. Configuration

Add a top-level `githubApps:` map, parallel to `githubOrgs:`:

```yaml
githubApps:
  my-runtime:                           # logical name, referenced by workstreams
    appId: "123456"
    installationId: "78901234"
    privateKeySecret: "github-app-my-runtime-key"   # ar-secrets secret name
```

The App private key is **never** written into `workstreams.yaml`. Only a
reference to an ar-secrets secret name is stored; the controller renders the
key at startup (or on demand) via the secrets MCP and keeps it in memory.

A workstream selects an App instead of an org token, and may optionally
restrict the minted token to specific repos:

```yaml
workstreams:
  - channelId: "C0123456789"
    defaultBranch: "main"
    repoUrl: "https://github.com/my-org/some-repo.git"
    githubApp: "my-runtime"             # use the App instead of githubOrg
    # scopeToRepo defaults to the repoUrl's repo when omitted.
    # scopeToRepos:                      # optional explicit list
    #   - "my-org/some-repo"
```

Rules:
- `githubApp` and `githubOrg` are mutually exclusive on a workstream.
- When `githubApp` is set and no explicit `scopeToRepos` is given, the minted
  installation token is scoped to the single repo named in `repoUrl` (plus any
  `dependentRepos`).

## 4. Token minting and lifecycle

The controller owns an `InstallationTokenProvider` keyed by
`(appName, scopeToRepos)`:

1. On first use, build the App JWT from the in-memory private key (10-minute
   JWT TTL is fine — it is only used to call the token endpoint).
2. Call `POST /app/installations/{installationId}/access_tokens` with the repo
   scope. Cache the returned token and its `expires_at`.
3. Reuse the cached token until a few minutes before expiry, then refresh.
4. Hand the token to whatever needs it:
   - **API proxy**: `FlowTreeApiEndpoint`'s GitHub proxy uses the installation
     token for that workstream instead of the org PAT.
   - **Git transport**: inject as the HTTPS credential for the job's clone/push
     (e.g. `https://x-access-token:<token>@github.com/...`, or via an askpass
     helper), in the `GitRepositorySetup` clone path.

`GitHubTokenValidator` is extended to validate App config at startup: confirm
the private key signs a usable JWT and that the installation resolves, instead
of (or in addition to) validating PATs.

## 5. Provisioning (optional capability)

An App with `Administration: write` lets an automated onboarding flow:

1. Create a repo: `POST /repos/{templateOwner}/{templateRepo}/generate` (gets
   scaffolding for free) or `POST /orgs/{org}/repos`.
2. Register a new workstream pointing at it (with `githubApp` set).
3. Subsequent jobs automatically get a token scoped to just that repo.

Provisioning is a **separate, higher-privilege path** from runtime. See §6.

## 6. Security model

- **Private key lives only on the controller**, rendered from ar-secrets,
  held in memory. It is never copied into an agent container, a workspace, or
  a log line.
- **Two privilege tiers, kept distinct:**
  - *Provisioning* credential (App permissions including `Administration:
    write`) — can create/delete repos; large blast radius; used only by the
    controller / an admin path.
  - *Runtime* credential (per-job installation token, scoped to one repo,
    ~1h TTL) — the only GitHub credential a job ever holds.
- Agents never receive the App JWT or the private key — only a scoped,
  short-lived installation token (or the proxy, which uses it on their behalf).
- A leaked installation token is low-impact: it expires within the hour and
  reaches only its scoped repo(s).

## 7. Integration points (files)

All in the `almostrealism/common` repo:

- `flowtree/runtime/src/main/resources/workstreams-example.yaml` — document
  `githubApps` and the per-workstream `githubApp` / `scopeToRepos` fields.
- `flowtree/runtime/src/main/java/io/flowtree/slack/GitHubTokenValidator.java`
  — validate App config; build the App-vs-PAT credential context.
- `FlowTreeController` — load `githubApps`, render the private key from
  ar-secrets, own the `InstallationTokenProvider`.
- `FlowTreeApiEndpoint` — GitHub proxy uses the installation token for
  App-backed workstreams.
- `flowtree/runtime/src/main/java/io/flowtree/jobs/GitRepositorySetup.java` /
  `flowtree/base/src/main/java/io/flowtree/jobs/GitOperations.java` — inject the
  installation token as the git transport credential for clone/push.
- `tools/mcp/secrets/server.py` — source of the App private key.

## 8. Phasing

1. **Config + validation.** Parse `githubApps`, render the private key from
   ar-secrets, validate the App/installation at startup. No behavior change yet.
2. **Token minting + API proxy.** Add `InstallationTokenProvider`; route the
   GitHub API proxy through it for App-backed workstreams.
3. **Git transport.** Inject the installation token into clone/push so jobs on
   App-backed workstreams use it instead of a PAT.
4. **Provisioning (optional).** Expose a controller path to create a repo from a
   template and register a workstream, for callers that need automated
   onboarding.

## 9. Open questions

1. **Token cache granularity.** Cache per `(app, repo-scope)` so two workstreams
   on the same repo share a token, or strictly per workstream? Per-scope is
   simpler and still safe.
2. **App-installed-on-all vs selected repos.** Installing on "all repositories"
   makes provisioned repos immediately usable without re-installing; installing
   on "selected" is tighter but needs an install update per new repo. The
   `repositories` down-scoping on the token endpoint works in both cases.
3. **Coexistence during migration.** Confirm a controller can hold both
   `githubOrgs` and `githubApps` and that per-workstream selection is
   unambiguous (it is mutually exclusive by rule, but validation should reject
   a workstream that sets both).
