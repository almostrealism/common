#!/usr/bin/env bash
# ─── Regression tests for validate-agent-commit.sh ──────────────
#
# Each case builds a throwaway repository with a master commit and a
# branch commit, runs the validator against it, and asserts the exit
# code. The repository is real rather than mocked because the validator
# reads the base and head images of every changed file out of git.
#
# Usage:
#   test-validate-agent-commit.sh
#
# Exit codes:
#   0  - all tests passed
#   1  - one or more tests failed

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VALIDATE="$SCRIPT_DIR/validate-agent-commit.sh"

PASS=0
FAIL=0
FAILED_TESTS=()

SECRET="this-is-only-a-test-shared-secret-1234567890"

# ── Helpers ─────────────────────────────────────────────────────

# The test class every case starts from on master.
base_test_class() {
    cat <<'JAVA'
package org.example;

import org.junit.Test;

public class ExampleTest extends TestSuiteBase {
    private int fixture = 4;

    private int helper(int value) {
        return value * fixture;
    }

    @Test(timeout = 60000)
    @TestDepth(2)
    public void addsSmallValues() {
        assertEquals(8, helper(2), 0.0001);
    }

    @Test(timeout = 60000)
    public void addsLargeValues() {
        assertEquals(4000, helper(1000), 0.0001);
    }
}
JAVA
}

expected_sig() {
    SECRET="$1" JOB_ID="$2" python3 - <<'PY'
import base64, hashlib, hmac, os
print(base64.urlsafe_b64encode(
    hmac.new(os.environ["SECRET"].encode("utf-8"),
             os.environ["JOB_ID"].encode("utf-8"),
             hashlib.sha256).digest()
).rstrip(b"=").decode("ascii"))
PY
}

# A class whose test methods share a name, to prove overloads stay
# separable rather than merging into one record.
overloaded_test_class() {
    cat <<'JAVA'
package org.example;

import org.junit.Test;

public class OverloadTest extends TestSuiteBase {
    @Test(timeout = 60000)
    public void handles() {
        handles(1);
    }

    @Test(timeout = 60000)
    public void handles(int value) {
        assertEquals(value, value, 0.0001);
    }
}
JAVA
}

# make_repo <dir> — a repository whose master holds the test class,
# a production source file, and a CI workflow, with a branch checked out.
make_repo() {
    local dir="$1" branch="$2"

    mkdir -p "$dir"
    git -C "$dir" init -q
    git -C "$dir" config user.email test@example.com
    git -C "$dir" config user.name "Test"

    mkdir -p "$dir/src/test/java/org/example" \
             "$dir/src/main/java/org/example" \
             "$dir/.github/workflows" \
             "$dir/tools/ci"
    base_test_class > "$dir/src/test/java/org/example/ExampleTest.java"
    overloaded_test_class > "$dir/src/test/java/org/example/OverloadTest.java"
    echo "public class Example { int value() { return 4; } }" \
        > "$dir/src/main/java/org/example/Example.java"
    echo "name: analysis" > "$dir/.github/workflows/analysis.yaml"
    echo "# coverage-history ledger" > "$dir/tools/ci/coverage-history.tsv"

    git -C "$dir" add -A
    git -C "$dir" commit -qm "initial"
    git -C "$dir" branch -M master
    git -C "$dir" checkout -qb "$branch"
}

# run_case NAME EXPECTED_EXIT BRANCH SECRET_VALUE MUTATE_FN [COMMIT_MSG] [BASE]
run_case() {
    local name="$1" expected_exit="$2" branch="$3" secret="$4" mutate="$5"
    local msg="${6:-agent commit}" base="${7:-master}"

    local dir actual_exit output
    dir=$(mktemp -d)
    make_repo "$dir" "$branch"

    "$mutate" "$dir"
    git -C "$dir" add -A
    git -C "$dir" commit -qm "$msg" --allow-empty

    actual_exit=0
    output=$(cd "$dir" && AR_AGENT_BYPASS_SECRET="$secret" \
        GITHUB_HEAD_REF="" GITHUB_REF_NAME="" \
        bash "$VALIDATE" "$base" --require-production-changes 2>&1) || actual_exit=$?

    if [ "$actual_exit" -eq "$expected_exit" ]; then
        PASS=$((PASS + 1))
        printf '  PASS  %s\n' "$name"
    else
        FAIL=$((FAIL + 1))
        FAILED_TESTS+=("$name (expected $expected_exit, got $actual_exit)")
        printf '  FAIL  %s  expected=%d got=%d\n' "$name" "$expected_exit" "$actual_exit"
        printf '%s\n' "$output" | sed 's/^/        /'
    fi

    rm -rf "$dir"
}

# ── Mutations ───────────────────────────────────────────────────

edit_production() {
    echo "public class Example { int value() { return 5; } }" \
        > "$1/src/main/java/org/example/Example.java"
}

append_test_method() {
    local file="$1/src/test/java/org/example/ExampleTest.java"
    python3 - "$file" <<'PY'
import sys
path = sys.argv[1]
text = open(path).read()
addition = """
    @Test(timeout = 60000)
    public void addsNegativeValues() {
        assertEquals(-8, helper(-2), 0.0001);
    }
"""
open(path, "w").write(text[:text.rindex("}")] + addition + "}\n")
PY
}

escalate_test_depth() {
    local file="$1/src/test/java/org/example/ExampleTest.java"
    sed -i.bak 's/@TestDepth(2)/@TestDepth(10)/' "$file" && rm -f "$file.bak"
}

weaken_tolerance() {
    local file="$1/src/test/java/org/example/ExampleTest.java"
    sed -i.bak 's/0\.0001/0.5/' "$file" && rm -f "$file.bak"
}

remove_test_method() {
    local file="$1/src/test/java/org/example/ExampleTest.java"
    python3 - "$file" <<'PY'
import sys
path = sys.argv[1]
text = open(path).read()
start = text.index("    @Test(timeout = 60000)\n    public void addsLargeValues")
end = text.index("}", text.index("assertEquals(4000")) + 2
open(path, "w").write(text[:start] + text[end:])
PY
}

edit_helper_only() {
    local file="$1/src/test/java/org/example/ExampleTest.java"
    sed -i.bak 's/return value \* fixture;/return fixture * value;/' "$file" && rm -f "$file.bak"
}

add_new_test_file() {
    cat > "$1/src/test/java/org/example/AnotherTest.java" <<'JAVA'
package org.example;

import org.junit.Test;

public class AnotherTest extends TestSuiteBase {
    @Test(timeout = 60000)
    public void works() {
        assertTrue(true);
    }
}
JAVA
}

add_overload() {
    local file="$1/src/test/java/org/example/OverloadTest.java"
    python3 - "$file" <<'ADDOVERLOAD'
import sys
path = sys.argv[1]
text = open(path).read()
addition = """
    @Test(timeout = 60000)
    public void handles(String value) {
        assertNotNull(value);
    }
"""
open(path, "w").write(text[:text.rindex("}")] + addition + "}\n")
ADDOVERLOAD
    edit_production "$1"
}

edit_overload() {
    local file="$1/src/test/java/org/example/OverloadTest.java"
    sed -i.bak 's/assertEquals(value, value, 0\.0001);/assertEquals(value, value, 0.5);/' \
        "$file" && rm -f "$file.bak"
    edit_production "$1"
}

edit_ci_only() {
    echo "name: analysis (edited)" > "$1/.github/workflows/analysis.yaml"
}

append_coverage_ledger() {
    printf '2026-01-01T00:00:00Z\torg.example\tjava\t10.0\t15.0\n' \
        >> "$1/tools/ci/coverage-history.tsv"
}

add_new_test_file_and_ledger() {
    add_new_test_file "$1"
    append_coverage_ledger "$1"
}

edit_ci_file() {
    echo "name: analysis (edited)" > "$1/.github/workflows/analysis.yaml"
    edit_production "$1"
}

rename_test_file() {
    git -C "$1" mv src/test/java/org/example/ExampleTest.java \
                  src/test/java/org/example/RenamedTest.java
    sed -i.bak 's/class ExampleTest/class RenamedTest/' \
        "$1/src/test/java/org/example/RenamedTest.java"
    rm -f "$1/src/test/java/org/example/RenamedTest.java.bak"
    sed -i.bak 's/@TestDepth(2)/@TestDepth(10)/' \
        "$1/src/test/java/org/example/RenamedTest.java"
    rm -f "$1/src/test/java/org/example/RenamedTest.java.bak"
}

edit_helper_and_production() {
    edit_helper_only "$1"
    edit_production "$1"
}

escalate_and_production() {
    escalate_test_depth "$1"
    edit_production "$1"
}

# ── RULE 1: existing test methods are locked ────────────────────

echo "RULE 1 — test method write lock"
run_case "TestDepth escalation blocked"        2 feature/x "$SECRET" escalate_test_depth
run_case "tolerance weakening blocked"         2 feature/x "$SECRET" weaken_tolerance
run_case "test method removal blocked"         2 feature/x "$SECRET" remove_test_method
run_case "test class rename blocked"           2 feature/x "$SECRET" rename_test_file

echo "RULE 1 — permitted test work"
run_case "added test method allowed"           0 feature/x "$SECRET" append_test_method
run_case "helper edit allowed"                 0 feature/x "$SECRET" edit_helper_and_production
run_case "new test file allowed"               0 feature/x "$SECRET" add_new_test_file
run_case "production change allowed"           0 feature/x "$SECRET" edit_production
run_case "added overload allowed"              0 feature/x "$SECRET" add_overload
run_case "edited overload blocked"             2 feature/x "$SECRET" edit_overload

# ── RULE 2: substantive changes ─────────────────────────────────

echo "RULE 2 — substantive changes"
run_case "helper-only commit not substantive"  3 feature/x "$SECRET" edit_helper_only
run_case "added test is substantive"           0 feature/x "$SECRET" append_test_method

# ── RULE 3: CI lock and the ci/ exemption ───────────────────────

echo "RULE 3 — CI/workflow lock"
run_case "CI edit blocked on feature branch"   4 feature/x "$SECRET" edit_ci_file
run_case "CI edit allowed on ci/ branch"       0 ci/issue-1 "$SECRET" edit_ci_file
run_case "CI edit allowed on ci/issues/2"      0 ci/issues/2 "$SECRET" edit_ci_file
run_case "CI-only commit substantive on ci/"   0 ci/issue-1 "$SECRET" edit_ci_only
run_case "CI-only commit blocked elsewhere"    4 feature/x "$SECRET" edit_ci_only

# ── Pipeline data file allowance (coverage-qa rounds) ───────────
#
# tools/ci/coverage-history.tsv is an exact-path exemption from RULE 3:
# a coverage-qa round appends to it on every round, on an ordinary
# feature/qa branch, with no ci/ prefix and no signed bypass.

echo "RULE 3 — pipeline data file allowance"
run_case "new test + ledger append passes (typical coverage round)" \
    0 feature/x "$SECRET" add_new_test_file_and_ledger
run_case "ledger-only append not CI-blocked (flagged non-substantive)" \
    3 feature/x "$SECRET" append_coverage_ledger

# ── Sensitive-file bypass ───────────────────────────────────────

echo "Sensitive-file bypass"
SIG=$(expected_sig "$SECRET" "job-77")
run_case "signed trailer lifts RULE 1"         0 feature/x "$SECRET" escalate_and_production \
    "fix the test

Sensitive-File-Bypass: job-77=$SIG"
run_case "signed trailer lifts RULE 3"         0 feature/x "$SECRET" edit_ci_file \
    "adjust the pipeline

Sensitive-File-Bypass: job-77=$SIG"
run_case "forged trailer does not lift RULE 1" 2 feature/x "$SECRET" escalate_and_production \
    "fix the test

Sensitive-File-Bypass: job-77=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
run_case "trailer for another job rejected"    2 feature/x "$SECRET" escalate_and_production \
    "fix the test

Sensitive-File-Bypass: job-OTHER=$SIG"
run_case "valid trailer inert without secret"  2 feature/x "" escalate_and_production \
    "fix the test

Sensitive-File-Bypass: job-77=$SIG"

# ── Fail-closed behaviour ───────────────────────────────────────
#
# A diff that cannot be taken is not a clean branch. The validator has to
# stop rather than report an unvalidated commit as passing.

echo "Fail-closed behaviour"
run_case "unusable base ref fails closed"      1 feature/x "$SECRET" edit_production \
    "agent commit" "origin/no-such-base"

# ── Report ──────────────────────────────────────────────────────

echo ""
echo "passed: $PASS   failed: $FAIL"

if [ "$FAIL" -gt 0 ]; then
    echo ""
    echo "Failures:"
    for t in "${FAILED_TESTS[@]}"; do
        echo "  - $t"
    done
    exit 1
fi

exit 0
