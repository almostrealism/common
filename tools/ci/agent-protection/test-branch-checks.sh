#!/usr/bin/env bash
# ─── Regression tests for the branch checks ─────────────────────
#
# Covers the three checks a branch's change set is held to:
#   check-ci-file-lock.sh          CI/workflow files change only on ci/...
#   detect-python-test-hiding.sh   base-branch Python tests are not weakened
#   validate-agent-commit.sh       a change set is more than edits to
#                                  base-branch tests
#
# Each case builds a throwaway repository with a master commit and a
# branch commit, runs one check against it, and asserts the exit code.
# The repository is real rather than mocked because the checks read the
# base and head images of every changed file out of git.
#
# Usage:
#   test-branch-checks.sh
#
# Exit codes:
#   0  - all tests passed
#   1  - one or more tests failed

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VALIDATE="$SCRIPT_DIR/validate-agent-commit.sh"
CI_LOCK="$SCRIPT_DIR/check-ci-file-lock.sh"
PY_HIDING="$SCRIPT_DIR/detect-python-test-hiding.sh"

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

# The Python test module every case starts from on master.
base_python_test() {
    cat <<'PY'
import unittest


class ExampleTest(unittest.TestCase):
    def fixture(self):
        return 4

    def test_adds_small_values(self):
        self.assertEqual(8, self.fixture() * 2)
        self.assertTrue(self.fixture() > 0)

    def test_adds_large_values(self):
        self.assertEqual(4000, self.fixture() * 1000)
PY
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
             "$dir/.github/workflows"
    base_test_class > "$dir/src/test/java/org/example/ExampleTest.java"
    overloaded_test_class > "$dir/src/test/java/org/example/OverloadTest.java"
    mkdir -p "$dir/tools/example"
    base_python_test > "$dir/tools/example/test_example.py"
    echo "public class Example { int value() { return 4; } }" \
        > "$dir/src/main/java/org/example/Example.java"
    echo "name: analysis" > "$dir/.github/workflows/analysis.yaml"

    git -C "$dir" add -A
    git -C "$dir" commit -qm "initial"
    git -C "$dir" branch -M master
    git -C "$dir" checkout -qb "$branch"
}

# record NAME EXPECTED_EXIT ACTUAL_EXIT OUTPUT
record() {
    local name="$1" expected_exit="$2" actual_exit="$3" output="$4"

    if [ "$actual_exit" -eq "$expected_exit" ]; then
        PASS=$((PASS + 1))
        printf '  PASS  %s\n' "$name"
    else
        FAIL=$((FAIL + 1))
        FAILED_TESTS+=("$name (expected $expected_exit, got $actual_exit)")
        printf '  FAIL  %s  expected=%d got=%d\n' "$name" "$expected_exit" "$actual_exit"
        printf '%s\n' "$output" | sed 's/^/        /'
    fi
}

# run_check DIR SCRIPT SECRET_VALUE BASE — runs a check in DIR; prints its
# output and returns its exit code.
run_check() {
    (cd "$1" && AR_AGENT_BYPASS_SECRET="$3" \
        GITHUB_HEAD_REF="" GITHUB_REF_NAME="" GITHUB_OUTPUT="" \
        bash "$2" "$4" 2>&1)
}

# run_case NAME EXPECTED_EXIT SCRIPT BRANCH SECRET_VALUE MUTATE_FN [COMMIT_MSG] [BASE]
run_case() {
    local name="$1" expected_exit="$2" script="$3" branch="$4" secret="$5" mutate="$6"
    local msg="${7:-agent commit}" base="${8:-master}"

    local dir actual_exit output
    dir=$(mktemp -d)
    make_repo "$dir" "$branch"

    "$mutate" "$dir"
    git -C "$dir" add -A
    git -C "$dir" commit -qm "$msg" --allow-empty

    actual_exit=0
    output=$(run_check "$dir" "$script" "$secret" "$base") || actual_exit=$?
    record "$name" "$expected_exit" "$actual_exit" "$output"

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

edit_ci_only() {
    echo "name: analysis (edited)" > "$1/.github/workflows/analysis.yaml"
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

escalate_and_production() {
    escalate_test_depth "$1"
    edit_production "$1"
}

# ── Python mutations ────────────────────────────────────────────

py_remove_test_function() {
    python3 - "$1/tools/example/test_example.py" <<'PY'
import sys
path = sys.argv[1]
text = open(path).read()
open(path, "w").write(text[:text.index("    def test_adds_large_values")])
PY
}

py_rename_test_function() {
    sed -i 's/def test_adds_large_values/def adds_large_values/' \
        "$1/tools/example/test_example.py"
}

py_remove_assertion() {
    sed -i '/self.assertTrue(self.fixture() > 0)/d' \
        "$1/tools/example/test_example.py"
}

py_edit_test_body_only() {
    # An ordinary edit: the assertion is rewritten, not removed, and both
    # test functions stay.
    sed -i 's/self.assertEqual(8, self.fixture() \* 2)/self.assertEqual(12, self.fixture() * 3)/' \
        "$1/tools/example/test_example.py"
}

py_edit_test_body() {
    py_edit_test_body_only "$1"
    edit_production "$1"
}

edit_tools_ci() {
    mkdir -p "$1/tools/ci"
    echo "echo skip" > "$1/tools/ci/run-tests.sh"
    edit_production "$1"
}

py_append_test_function() {
    cat >> "$1/tools/example/test_example.py" <<'PY'

    def test_added_case(self):
        self.assertEqual(16, self.fixture() * 4)
PY
}

py_edit_fixture() {
    sed -i 's/return 4/return 5/' "$1/tools/example/test_example.py"
    edit_production "$1"
}

py_add_new_test_file() {
    cat > "$1/tools/example/test_added.py" <<'PY'
import unittest


class AddedTest(unittest.TestCase):
    def test_new_case(self):
        self.assertEqual(1, 1)
PY
}

py_remove_assertion_and_production() {
    py_remove_assertion "$1"
    edit_production "$1"
}

py_remove_function_and_production() {
    py_remove_test_function "$1"
    edit_production "$1"
}

# ── validate-agent-commit.sh: more than edits to base-branch tests ──
#
# Only a change set confined to existing test files, with no new test in
# them, is blocked. Whether an edit weakens a test is test-integrity-check's
# question, so an escalated @TestDepth that arrives with a production change
# passes here.

echo "validate-agent-commit — base-branch-test-only change sets"
run_case "TestDepth edit alone blocked"         3 "$VALIDATE" feature/x "$SECRET" escalate_test_depth
run_case "tolerance edit alone blocked"         3 "$VALIDATE" feature/x "$SECRET" weaken_tolerance
run_case "test removal alone blocked"           3 "$VALIDATE" feature/x "$SECRET" remove_test_method
run_case "helper edit alone blocked"            3 "$VALIDATE" feature/x "$SECRET" edit_helper_only
run_case "py test edit alone blocked"           3 "$VALIDATE" feature/x "$SECRET" py_edit_test_body_only
run_case "py assertion removal alone blocked"   3 "$VALIDATE" feature/x "$SECRET" py_remove_assertion

echo "validate-agent-commit — substantive change sets"
run_case "test edit with production allowed"    0 "$VALIDATE" feature/x "$SECRET" escalate_and_production
run_case "added test method allowed"            0 "$VALIDATE" feature/x "$SECRET" append_test_method
run_case "added overload allowed"               0 "$VALIDATE" feature/x "$SECRET" add_overload
run_case "new test file allowed"                0 "$VALIDATE" feature/x "$SECRET" add_new_test_file
run_case "test class rename allowed"            0 "$VALIDATE" feature/x "$SECRET" rename_test_file
run_case "production change allowed"            0 "$VALIDATE" feature/x "$SECRET" edit_production
run_case "py added test function allowed"       0 "$VALIDATE" feature/x "$SECRET" py_append_test_function
run_case "py new test file allowed"             0 "$VALIDATE" feature/x "$SECRET" py_add_new_test_file
run_case "CI-only change set not its concern"   0 "$VALIDATE" feature/x "$SECRET" edit_ci_only

# The branch only adds a new test method. Master, unrelated to the branch,
# evolves the SAME file afterward (its own @TestDepth bump). Comparing
# against master's current tip would make that master-side edit look like
# the branch's own; comparing against the merge-base does not.
run_merge_base_case() {
    local name="$1" expected_exit="$2"
    local dir actual_exit=0 output
    dir=$(mktemp -d)
    make_repo "$dir" feature/x

    append_test_method "$dir"
    git -C "$dir" add -A
    git -C "$dir" commit -qm "add a new test method"

    git -C "$dir" checkout -q master
    escalate_test_depth "$dir"
    git -C "$dir" add -A
    git -C "$dir" commit -qm "master: bump TestDepth independently of the branch"
    git -C "$dir" checkout -q feature/x

    output=$(run_check "$dir" "$VALIDATE" "$SECRET" master) || actual_exit=$?
    record "$name" "$expected_exit" "$actual_exit" "$output"
    rm -rf "$dir"
}

echo "validate-agent-commit — merge-base, not base-branch tip"
run_merge_base_case "branch-only addition allowed despite master's own later edit" 0

# ── detect-python-test-hiding.sh ─────────────────────────────────
#
# The two ways a Python test stops testing are findings; editing the inside
# of one is not.

echo "detect-python-test-hiding — findings"
run_case "py test function removal found"       2 "$PY_HIDING" feature/x "$SECRET" py_remove_function_and_production
run_case "py test function rename found"        2 "$PY_HIDING" feature/x "$SECRET" py_rename_test_function
run_case "py assertion removal found"           2 "$PY_HIDING" feature/x "$SECRET" py_remove_assertion_and_production

echo "detect-python-test-hiding — permitted Python test work"
run_case "py test body edit allowed"            0 "$PY_HIDING" feature/x "$SECRET" py_edit_test_body
run_case "py added test function allowed"       0 "$PY_HIDING" feature/x "$SECRET" py_append_test_function
run_case "py fixture edit allowed"              0 "$PY_HIDING" feature/x "$SECRET" py_edit_fixture
run_case "py new test file allowed"             0 "$PY_HIDING" feature/x "$SECRET" py_add_new_test_file
run_case "Java test edits not its concern"      0 "$PY_HIDING" feature/x "$SECRET" escalate_test_depth

# ── check-ci-file-lock.sh ─────────────────────────────────────────

echo "check-ci-file-lock — the lock and the ci/ exemption"
run_case "workflow edit blocked on feature"     4 "$CI_LOCK" feature/x "$SECRET" edit_ci_file
run_case "workflow-only edit blocked"           4 "$CI_LOCK" feature/x "$SECRET" edit_ci_only
run_case "tools/ci edit blocked on feature"     4 "$CI_LOCK" feature/x "$SECRET" edit_tools_ci
run_case "workflow edit allowed on ci/"         0 "$CI_LOCK" ci/issue-1 "$SECRET" edit_ci_file
run_case "workflow edit allowed on ci/a/b"      0 "$CI_LOCK" ci/issues/2 "$SECRET" edit_ci_file
run_case "no CI change allowed"                 0 "$CI_LOCK" feature/x "$SECRET" edit_production

echo "check-ci-file-lock — sensitive-file bypass"
SIG=$(expected_sig "$SECRET" "job-77")
run_case "signed trailer lifts the lock"        0 "$CI_LOCK" feature/x "$SECRET" edit_ci_file \
    "adjust the pipeline

Sensitive-File-Bypass: job-77=$SIG"
run_case "forged trailer does not"              4 "$CI_LOCK" feature/x "$SECRET" edit_ci_file \
    "adjust the pipeline

Sensitive-File-Bypass: job-77=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
run_case "trailer for another job rejected"     4 "$CI_LOCK" feature/x "$SECRET" edit_ci_file \
    "adjust the pipeline

Sensitive-File-Bypass: job-OTHER=$SIG"
run_case "valid trailer inert without secret"   4 "$CI_LOCK" feature/x "" edit_ci_file \
    "adjust the pipeline

Sensitive-File-Bypass: job-77=$SIG"

# ── Fail-closed behaviour ───────────────────────────────────────
#
# A diff that cannot be taken is not a clean branch: every check has to
# stop rather than report an unchecked change set as passing.

echo "Fail-closed behaviour"
run_case "validator: unusable base fails closed"  1 "$VALIDATE" feature/x "$SECRET" edit_production \
    "agent commit" "origin/no-such-base"
run_case "py hiding: unusable base fails closed"  1 "$PY_HIDING" feature/x "$SECRET" edit_production \
    "agent commit" "origin/no-such-base"
run_case "CI lock: unusable base fails closed"    1 "$CI_LOCK" feature/x "$SECRET" edit_ci_file \
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
