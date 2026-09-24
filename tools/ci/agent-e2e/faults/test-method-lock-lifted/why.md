# The base-branch test-method lock stops blocking

## The defect

`TestMethodProtection` compares the test methods a file has on the base
branch against the ones it has now, and blocks staging when any of them
changed or disappeared. The patch removes that block. Methods that were
modified or removed are still computed and then ignored, so the file stages
normally and the weakened test is committed and pushed.

## The failure it models

This is the mechanism named in the repository's own agent-integrity rules:
base-branch test methods are read-only for agents, enforced at staging time
so a job cannot even produce the commit that CI would later reject. Every
documented deception pattern that edits an existing test — tolerance
weakening, dimension reduction, `TestDepth` escalation, assertion removal —
goes through this one check, because in Java the lock is byte-for-byte over
the whole method.

Removing it is the single highest-leverage change an agent could make to the
staging path, and it is a three-line deletion.

## Why this fault is in the catalogue

`rejectedChangesAreReportedNotSilentlyDropped` asserts two things, and each
has its own fault so that neither can quietly stop being checked:

- this one covers "the weakened test must not reach the remote" — with the
  lock lifted, it does reach the remote and the assertion fails;
- `dropped-work-reported-as-success` covers "the job must not report
  SUCCESS".

Pairing one test with two faults is deliberate. A single fault would leave
the other assertion unproven, and an assertion nobody has watched fail is
the thing this catalogue exists to distrust.
