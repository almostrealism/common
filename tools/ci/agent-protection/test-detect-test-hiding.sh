#!/usr/bin/env bash
# ─── Regression tests for detect-test-hiding.sh ───────────────────────────
#
# Usage:
#   test-detect-test-hiding.sh
#
# Creates temporary git repositories, populates them with synthetic commits
# that represent known true-positive and false-positive scenarios, and verifies
# that detect-test-hiding.sh produces the expected exit code for each.
#
# Exit codes:
#   0 - all tests passed
#   1 - one or more tests failed

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DETECT="$SCRIPT_DIR/detect-test-hiding.sh"

PASS=0
FAIL=0

# ── Helpers ────────────────────────────────────────────────────────────────

# run_case NAME EXPECTED_EXIT SETUP_FN
# Runs SETUP_FN inside a fresh temporary git repo (on a feature branch whose
# base is "master"), then runs detect-test-hiding.sh with "master" as the
# base branch and asserts the exit code equals EXPECTED_EXIT.
run_case() {
    local name="$1"
    local expected_exit="$2"
    local setup_fn="$3"

    local tmpdir
    tmpdir=$(mktemp -d)

    (
        cd "$tmpdir"
        git init -q
        git config user.email "test@test.com"
        git config user.name "Test"
        "$setup_fn"
    )

    local actual_exit=0
    (cd "$tmpdir" && bash "$DETECT" "master" 2>/dev/null) || actual_exit=$?

    rm -rf "$tmpdir"

    if [ "$actual_exit" -eq "$expected_exit" ]; then
        echo "  PASS: $name (exit $actual_exit)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $name — expected exit $expected_exit, got $actual_exit"
        FAIL=$((FAIL + 1))
    fi
}

# Creates base commit on "master", then a change commit on "feature".
# detect-test-hiding.sh is run on the feature branch with master as base.
scenario_modify() {
    local base_content="$1"
    local head_content="$2"
    local filename="${3:-MyTest.java}"

    mkdir -p src/test/java
    printf '%s\n' "$base_content" > "src/test/java/${filename}"
    git add .
    git commit -q -m "base"
    git branch -M master

    git checkout -q -b feature
    printf '%s\n' "$head_content" > "src/test/java/${filename}"
    git add .
    git commit -q -m "change"
}

# ── Scenario: assert in Javadoc removed — FALSE-POSITIVE REGRESSION ────────
#
# A Javadoc comment containing the English word "assert" is removed together
# with the field it documents. No actual assertion calls are deleted. The
# script MUST NOT fire NET_ASSERTIONS_REMOVED (expected exit 0).
#
# This was the bug: Pattern 2's grep matched "assert" as an English word
# inside the removed `/** ... assert log output. */` Javadoc line.
setup_javadoc_assert_prose() {
    scenario_modify \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    /** Sufficient for tests that do not assert log output. */
    private static final Object SILENT = new Object();

    @Test(timeout = 5000)
    public void testA() {
        assertEquals(1, 1);
    }
}' \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {

    @Test(timeout = 5000)
    public void testA() {
        assertEquals(1, 1);
    }
}'
}

# ── Scenario: real assertion removed — TRUE POSITIVE ───────────────────────
#
# A real assertEquals call is deleted from an existing test method. The script
# MUST report NET_ASSERTIONS_REMOVED (expected exit 2).
setup_real_assertion_removed() {
    scenario_modify \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testA() {
        assertEquals(1, 1);
        assertEquals(2, 2);
    }
}' \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testA() {
        assertEquals(1, 1);
    }
}'
}

# ── Scenario: new test with assert in Javadoc — NO FALSE POSITIVE ──────────
#
# A new test method is added whose Javadoc contains the word "assert". Existing
# assertions are untouched. Expected exit 0.
setup_new_method_javadoc_assert() {
    scenario_modify \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testA() {
        assertEquals(1, 1);
    }
}' \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testA() {
        assertEquals(1, 1);
    }

    /** New test; verifies we do not assert incorrect values. */
    @Test(timeout = 5000)
    public void testB() {
        assertEquals(2, 2);
    }
}'
}

# ── Scenario: new test method with a higher timeout — FALSE-POSITIVE REGRESSION ──
#
# An existing test keeps its timeout (5000); only its `expected=` clause is
# dropped. A brand-new test method is added with a legitimately larger timeout
# (30000). The removed `timeout = 5000` line must NOT be mis-paired against the
# new method's `timeout = 30000`. The script MUST NOT fire TIMEOUT_INFLATED
# (expected exit 0).
setup_new_method_higher_timeout() {
    scenario_modify \
'package test;
import org.junit.Test;
public class MyTest {
    @Test(timeout = 5000, expected = RuntimeException.class)
    public void testA() {
        throw new RuntimeException();
    }
}' \
'package test;
import org.junit.Test;
public class MyTest {
    @Test(timeout = 5000)
    public void testA() {
        // no longer expects a throw
    }

    @Test(timeout = 30000)
    public void testB() {
        // brand-new test that is legitimately slower
    }
}'
}

# ── Scenario: existing method timeout inflated >2x — TRUE POSITIVE ──────────
#
# An EXISTING test method's timeout is raised from 5000 to 30000. The script
# MUST report TIMEOUT_INFLATED (expected exit 2).
setup_existing_timeout_inflated() {
    scenario_modify \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testA() {
        assertEquals(1, 1);
    }
}' \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 30000)
    public void testA() {
        assertEquals(1, 1);
    }
}'
}

# ── Scenario: RENAME a test method AND inflate its timeout — TRUE POSITIVE ──
#
# The dodge attempt: rename testFoo (timeout 5000) to testFooV2 and bump the
# timeout to 60000 in one change. Because the comparison is by value (not by
# method name), the removed 5000 does not cancel against the added 60000, so
# the inflation is still caught. The script MUST report TIMEOUT_INFLATED
# (expected exit 2).
setup_rename_and_inflate() {
    scenario_modify \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testFoo() {
        assertEquals(1, 1);
    }
}' \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 60000)
    public void testFooV2() {
        assertEquals(1, 1);
    }
}'
}

# ── Scenario: RENAME a test method, timeout unchanged — NO FALSE POSITIVE ───
#
# A legitimate rename that keeps the same timeout. The removed 5000 cancels the
# added 5000, so nothing flags (expected exit 0).
setup_rename_no_inflate() {
    scenario_modify \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testFoo() {
        assertEquals(1, 1);
    }
}' \
'package test;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
public class MyTest {
    @Test(timeout = 5000)
    public void testFooV2() {
        assertEquals(1, 1);
    }
}'
}

# ── Scenario: no test files changed — CLEAN BRANCH ─────────────────────────
setup_no_test_changes() {
    mkdir -p src/main/java
    echo 'public class Foo {}' > src/main/java/Foo.java
    git add .
    git commit -q -m "base"
    git branch -M master

    git checkout -q -b feature
    echo 'public class Foo { int x = 1; }' > src/main/java/Foo.java
    git add .
    git commit -q -m "change"
}

# ── Run all cases ──────────────────────────────────────────────────────────

echo "Running detect-test-hiding regression tests..."
echo ""

run_case "javadoc-assert-prose [false-positive regression]" 0 setup_javadoc_assert_prose
run_case "real-assertion-removed [true positive]"           2 setup_real_assertion_removed
run_case "new-method-with-assert-javadoc [no false positive]" 0 setup_new_method_javadoc_assert
run_case "new-method-higher-timeout [false-positive regression]" 0 setup_new_method_higher_timeout
run_case "existing-timeout-inflated [true positive]"        2 setup_existing_timeout_inflated
run_case "rename-and-inflate [true positive / dodge caught]" 2 setup_rename_and_inflate
run_case "rename-no-inflate [no false positive]"            0 setup_rename_no_inflate
run_case "no-test-changes [clean branch]"                   0 setup_no_test_changes

echo ""
if [ "$FAIL" -eq 0 ]; then
    echo "All ${PASS} test(s) passed."
    exit 0
else
    echo "${FAIL} test(s) FAILED, ${PASS} passed."
    exit 1
fi
