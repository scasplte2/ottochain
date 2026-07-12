---
name: test-audit
description: Sample ottochain test suites and prove they actually test — mutate each suite's subject, confirm the test goes red, then revert. Files issues for decorative/tautological/self-regenerating tests. Use to check test integrity, not just coverage.
---

# test-audit

Coverage % says a line ran; it does not say a test would catch a bug. This skill proves it the only way
that counts: break the code the test covers and confirm the test fails. Applies the
`ai-smells-test-integrity` persona checklist.

## Sequence
1. `bin/worksheet test-audit-<date>` — open a worksheet for findings.
2. Pick a sample of suites — prioritize the guards named in `docs/signing-canonical-and-validation.md`
   and any suite touched by recent diffs. Good starting targets on `main`: `CommittedViewSuite`,
   signing-canonical suites, `FailureReasonCanonicalSuite`, genesis contract suites.
3. Confirm each suite is GREEN as-is: `bin/test <module> "*<SuiteName>*"`.
4. **The mutation check (the core of this skill):** for each suite, make a small, targeted mutation to the
   PRODUCTION code it covers — flip a comparator (`>`→`>=`), negate a boolean, return a constant, drop a
   `sortBy`, skip a dedup. Re-run the suite:
   - **Suite goes RED** → the test is real. Good. **Revert the mutation** (`git checkout -- <file>`).
   - **Suite stays GREEN** → the test is decorative for that behavior. Record it as a finding, revert.
   ALWAYS revert every mutation before moving on — leave the tree clean.
5. Apply the rest of the persona checklist by reading:
   - self-regenerating golden fixtures (fixture written and asserted in the same run),
   - tautological assertions (asserting a mock returns what it was told),
   - vacuous property generators, error-message-string drift, weaver `name` shadowing.
6. File a `gh issue` per weak suite (title lowercase ≤72, label `test-integrity`), citing the mutation
   that survived. Link findings in the worksheet. Append a `docs/agents/feedback.md` entry if a whole
   class of behavior is untested.

## Rules
- Never leave a mutation in the tree. If interrupted, `git status` + revert before stopping.
- Do not "fix" the weak test inline during the audit — file it; the fix is a separate reviewed PR.
- A suite that goes red under every plausible mutation is a PASS — record that too, it's signal.

## Future backbone
This is manual mutation testing. The durable version is **stryker4s** (mutation testing for Scala) run in
CI — a mutation score is a deterministic, non-flaky "do the tests catch bugs" gate, unlike the advisory
70% coverage number. A stryker4s spike is proposed in `docs/agents/process-observations.md` (#4); until it
lands, this skill is how we get the signal.
