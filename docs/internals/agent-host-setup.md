# Agent Host Setup

What an account needs on a machine before an agent session running as that
account can finish work without a person stepping in. Each item below was
found missing on a real host, and each cost a session either a hand-off to a
person or a check it could not run.

This covers the host and the account. What the agent's tools must provide is
tracked separately, in the tracker.

## Checklist

| Requirement | How to check | What goes wrong without it |
|---|---|---|
| The account has its own SSH key, registered with the Git host, and the host's key in `known_hosts` | `ssh -T git@github.com` | Commits succeed and every push fails. Sessions report work as done that nobody else can see |
| Git recent enough for `git diff --merge-base` | `git diff --merge-base HEAD HEAD` exits 0 | The pre-finish checks in `CLAUDE.md` (inline comments, temporary identifiers) cannot be run as written, and several `tools/tests` suites fail for reasons unrelated to the change |
| A `date` that accepts `-d` | `date -d '1 hour ago'` exits 0 | Shell suites under `tools/ci` that compute times fail on macOS, whose `date` has no `-d`. Install GNU coreutils and put it ahead of the system one for the account |
| `AR_PRIVATE_PRODUCT_NAMES` set for the commit tool | The commit tool's result carries no warning about it | The private-product-name check is skipped on every commit, silently except for that warning |
| Maven artifacts built from the branch being tested | Install after every branch switch: `mvn install -DskipTests -pl <module> -am` | The local repository is shared by every branch. A test run with `-pl` and no `-am` resolves jars built from whichever branch was installed last, so a result can describe other code entirely |
| The tool servers and hooks restarted after a change to them | Restart the session | A running commit tool or hook keeps the code it loaded at start. A new check does not apply until then, and a session can believe it is enforced |

## Things a single host cannot give you

These are limits to state in a report, not problems to work around.

- **A compiler that behaves like every CI runner's.** Compilers differ in the
  limits they enforce. A kernel that compiles locally can be rejected on
  another runner, so a local pass does not settle a compile failure seen in
  CI. Only the run on that runner does.
- **Every backend.** A macOS host runs the Metal and native lanes. A failure
  on OpenCL or CUDA is diagnosed from the recorded output of the failing run.
- **Every agent runtime.** A plugin for a runtime that is not installed on the
  host is written but not exercised. Say so.

## Reading generated kernel source

A test run hands back a directory of generated source. Do not read it with
shell commands: a hook refuses that, because the raw files have lost the link
between an operation and its source. Use `ar-profile-analyzer`.

The analyzer reads an operation profile, and most tests do not write one. When
the test you ran has no profile, the choice is between writing a small profiled
reproduction of the same computation and reasoning from the code that builds
the expression. Reasoning from code is weaker evidence and should be reported
as such. If the question is about the shape of the generated source, write the
profiled reproduction.
