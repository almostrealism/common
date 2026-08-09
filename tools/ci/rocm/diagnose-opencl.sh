#!/usr/bin/env bash
# Deliberately not `set -e`: every probe below is expected to be able to fail,
# and a failing probe is a result rather than a reason to stop.
set -uo pipefail

# ─── Why can the JVM not get an OpenCL context? ──────────────────────
#
# The entrypoint's preflight proves that `clinfo` — a C program — can enumerate
# the platform. That is not the same question as whether the JVM's JOCL binding
# can obtain a context under the environment a CI step actually runs with, and
# the two have already diverged once: the test steps REPLACE LD_LIBRARY_PATH
# rather than appending to it, discarding the image's ROCm paths.
#
# This script answers both questions side by side, and writes everything to a
# file so nothing is lost to scrollback.
#
# Run it on the host, as the service account:
#
#   sudo -iu ar-ci
#   cd ~/common/tools/ci/rocm
#   ./diagnose-opencl.sh
#
# Options:
#   --out FILE      Where to write the report (default: ~/opencl-diagnosis-<ts>.log)
#   --image NAME    Image to probe (default: ar-ci-cl-runner:latest)
#   --ci-libs PATH  The value CI assigns to LD_LIBRARY_PATH, to reproduce the
#                   stripped environment (default: /tmp/ar_libs)
#   --jocl-jar PATH Explicit jocl jar; otherwise searched for under ~/.m2-ci and ~/.m2
#   --in-container  Internal — run the probes directly rather than launching a container
#   -h, --help      Show this help

IMAGE="ar-ci-cl-runner:latest"
CI_LIBS="/tmp/ar_libs"
OUT=""
JOCL_JAR=""
IN_CONTAINER=0

usage() { sed -n '6,30p' "$0" | sed 's/^# \{0,1\}//'; }

while [ $# -gt 0 ]; do
    case "$1" in
        --out)          OUT="$2"; shift 2 ;;
        --image)        IMAGE="$2"; shift 2 ;;
        --ci-libs)      CI_LIBS="$2"; shift 2 ;;
        --jocl-jar)     JOCL_JAR="$2"; shift 2 ;;
        --in-container) IN_CONTAINER=1; shift ;;
        -h|--help)      usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
done

section() {
    echo
    echo "════════════════════════════════════════════════════════════"
    echo "  $1"
    echo "════════════════════════════════════════════════════════════"
}

# Results are emitted as stable name=value pairs so the host side can summarise
# them by grep without parsing prose.
result() { echo "RESULT $1=$2"; }

# ─────────────────────────────────────────────────────────────────────
# Everything below --in-container runs INSIDE the runner image.
# ─────────────────────────────────────────────────────────────────────
run_probes() {
    section "Environment"
    echo "date:            $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
    echo "uname:           $(uname -a)"
    echo "arch:            $(uname -m)"
    echo "id:              $(id)"
    echo "LD_LIBRARY_PATH: ${LD_LIBRARY_PATH:-<unset>}"
    echo "simulated CI LD_LIBRARY_PATH: ${CI_LIBS}"

    section "ICD registration"
    echo "--- /etc/OpenCL/vendors (what this image registered) ---"
    ls -la /etc/OpenCL/vendors/ 2>&1
    for f in /etc/OpenCL/vendors/*.icd; do
        [ -f "$f" ] || continue
        echo "  ${f}: $(cat "$f")"
    done

    echo
    echo "--- /opt/rocm/etc/OpenCL/vendors (what ROCm ships) ---"
    ls -la /opt/rocm/etc/OpenCL/vendors/ 2>&1
    for f in /opt/rocm/etc/OpenCL/vendors/*.icd; do
        [ -f "$f" ] || continue
        echo "  ${f}: $(cat "$f")"
    done

    section "The registered implementation library"
    ICD_TARGET=$(cat /etc/OpenCL/vendors/*.icd 2>/dev/null | head -1)
    echo "ICD names: ${ICD_TARGET:-<none>}"

    if [ -n "${ICD_TARGET}" ] && [ -f "${ICD_TARGET}" ]; then
        result icd_target_exists yes
        ls -lL "${ICD_TARGET}"
        echo
        echo "--- ldd, with the image's LD_LIBRARY_PATH ---"
        ldd "${ICD_TARGET}" 2>&1
        MISSING_DEFAULT=$(ldd "${ICD_TARGET}" 2>&1 | grep -ci 'not found')
        result icd_missing_deps_default "${MISSING_DEFAULT}"

        echo
        echo "--- ldd, with LD_LIBRARY_PATH as CI sets it (${CI_LIBS}) ---"
        LD_LIBRARY_PATH="${CI_LIBS}" ldd "${ICD_TARGET}" 2>&1
        MISSING_CI=$(LD_LIBRARY_PATH="${CI_LIBS}" ldd "${ICD_TARGET}" 2>&1 | grep -ci 'not found')
        result icd_missing_deps_ci_env "${MISSING_CI}"
    else
        result icd_target_exists no
        echo "The ICD names a path that does not exist. Candidates present:"
        find /opt/rocm -name 'libamdocl*' 2>/dev/null | sed 's/^/  /'
    fi

    # Capture before matching. clinfo exits non-zero on some builds even when it
    # has printed a perfectly good listing, and under `set -o pipefail` a
    # `clinfo | grep` pipeline inherits that status — reporting FAIL against
    # output that plainly contains a platform.
    section "clinfo — image environment"
    CLINFO_DEFAULT=$(clinfo 2>&1)
    printf '%s\n' "${CLINFO_DEFAULT}"
    if printf '%s' "${CLINFO_DEFAULT}" | grep -qi 'platform name'; then
        result clinfo_default OK
    else
        result clinfo_default FAIL
    fi

    section "clinfo — LD_LIBRARY_PATH replaced, as the CI step does"
    echo "(LD_LIBRARY_PATH=${CI_LIBS})"
    CLINFO_CI=$(LD_LIBRARY_PATH="${CI_LIBS}" clinfo 2>&1)
    printf '%s\n' "${CLINFO_CI}"
    if printf '%s' "${CLINFO_CI}" | grep -qi 'platform name'; then
        result clinfo_ci_env OK
    else
        result clinfo_ci_env FAIL
    fi

    # The decisive probe. clinfo exercises the ICD loader from C; this exercises
    # it the way the tests do — through JOCL's JNI binding inside a JVM — and
    # prints the full cause chain, which Hardware.java discards.
    section "JOCL — can the JVM obtain a platform?"
    if [ ! -f /tmp/jocl.jar ]; then
        echo "No jocl jar was mounted, so this probe is skipped."
        echo "Re-run with --jocl-jar PATH, or build the project first so that"
        echo "a jocl jar exists under ~/.m2-ci or ~/.m2 on the host."
        result jocl_default SKIPPED
        result jocl_ci_env SKIPPED
        return
    fi

    mkdir -p /tmp/joclprobe && cd /tmp/joclprobe || return
    cat > JoclProbe.java <<'JAVA'
import org.jocl.CL;
import org.jocl.cl_platform_id;

/** Minimal probe: can this JVM reach an OpenCL platform through JOCL? */
public class JoclProbe {
    public static void main(String[] args) {
        try {
            int[] count = new int[1];
            CL.clGetPlatformIDs(0, null, count);
            System.out.println("platforms=" + count[0]);

            if (count[0] > 0) {
                cl_platform_id[] platforms = new cl_platform_id[count[0]];
                CL.clGetPlatformIDs(count[0], platforms, null);
                int[] devices = new int[1];
                CL.clGetDeviceIDs(platforms[0], CL.CL_DEVICE_TYPE_ALL, 0, null, devices);
                System.out.println("devices=" + devices[0]);
                System.out.println("JOCL_OK");
            } else {
                System.out.println("JOCL_FAIL no platforms enumerated");
            }
        } catch (Throwable t) {
            System.out.println("JOCL_FAIL " + t);
            for (Throwable c = t.getCause(); c != null; c = c.getCause()) {
                System.out.println("  caused by: " + c);
            }
            for (StackTraceElement e : t.getStackTrace()) {
                System.out.println("    at " + e);
            }
        }
    }
}
JAVA

    javac -cp /tmp/jocl.jar JoclProbe.java 2>&1 || {
        echo "javac failed; cannot run the JOCL probe."
        result jocl_default SKIPPED
        result jocl_ci_env SKIPPED
        return
    }

    echo "--- image environment ---"
    JOCL_DEFAULT=$(java -cp "/tmp/jocl.jar:." JoclProbe 2>&1)
    printf '%s\n' "${JOCL_DEFAULT}"
    if printf '%s' "${JOCL_DEFAULT}" | grep -q JOCL_OK; then
        result jocl_default OK
    else
        result jocl_default FAIL
    fi

    echo
    echo "--- LD_LIBRARY_PATH replaced, as the CI step does (${CI_LIBS}) ---"
    JOCL_CI=$(LD_LIBRARY_PATH="${CI_LIBS}" java -cp "/tmp/jocl.jar:." JoclProbe 2>&1)
    printf '%s\n' "${JOCL_CI}"
    if printf '%s' "${JOCL_CI}" | grep -q JOCL_OK; then
        result jocl_ci_env OK
    else
        result jocl_ci_env FAIL
    fi
}

if [ "${IN_CONTAINER}" = 1 ]; then
    run_probes
    exit 0
fi

# ─────────────────────────────────────────────────────────────────────
# Host side: launch the image with the same flags the runner uses.
# ─────────────────────────────────────────────────────────────────────

SCRIPT_PATH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"
[ -z "${OUT}" ] && OUT="${HOME}/opencl-diagnosis-$(date +%Y%m%d-%H%M%S).log"

if ! command -v podman > /dev/null 2>&1; then
    echo "ERROR: podman is not available." >&2
    exit 1
fi

if [ -z "${JOCL_JAR}" ]; then
    JOCL_JAR=$(find "${HOME}/.m2-ci" "${HOME}/.m2" -name 'jocl-*.jar' 2>/dev/null | head -1)
fi

MOUNTS=(-v "${SCRIPT_PATH}:/tmp/diagnose-opencl.sh:ro" -v /opt/rocm:/opt/rocm:ro)
if [ -n "${JOCL_JAR}" ] && [ -f "${JOCL_JAR}" ]; then
    MOUNTS+=(-v "${JOCL_JAR}:/tmp/jocl.jar:ro")
    JOCL_NOTE="using ${JOCL_JAR}"
else
    JOCL_NOTE="none found — the JVM probe will be skipped"
fi

echo "Probing ${IMAGE}"
echo "  jocl jar: ${JOCL_NOTE}"
echo "  report:   ${OUT}"
echo

podman run --rm --entrypoint bash \
    --device /dev/kfd --device /dev/dri --group-add keep-groups \
    "${MOUNTS[@]}" \
    "${IMAGE}" /tmp/diagnose-opencl.sh --in-container --ci-libs "${CI_LIBS}" \
    > "${OUT}" 2>&1

echo "─────────────────────────────────────────────"
echo "Summary"
echo "─────────────────────────────────────────────"
grep '^RESULT ' "${OUT}" | sed 's/^RESULT /  /' || echo "  (no results — the container did not run; see the report)"
echo

# The comparison that matters: the same probe under the image's environment and
# under the one CI actually imposes.
DEF=$(grep -o '^RESULT jocl_default=.*' "${OUT}" | cut -d= -f2)
CI=$(grep -o '^RESULT jocl_ci_env=.*' "${OUT}" | cut -d= -f2)
CDEF=$(grep -o '^RESULT clinfo_default=.*' "${OUT}" | cut -d= -f2)
CCI=$(grep -o '^RESULT clinfo_ci_env=.*' "${OUT}" | cut -d= -f2)

if [ "${CDEF}" = "OK" ] && [ "${CCI}" = "FAIL" ]; then
    echo "  clinfo works under the image environment and fails under CI's."
    echo "  That implicates LD_LIBRARY_PATH being replaced rather than appended."
elif [ "${DEF}" = "OK" ] && [ "${CI}" = "FAIL" ]; then
    echo "  JOCL works under the image environment and fails under CI's."
    echo "  That implicates LD_LIBRARY_PATH being replaced rather than appended."
elif [ "${DEF}" = "FAIL" ] && [ "${CDEF}" = "OK" ]; then
    echo "  clinfo works but JOCL does not, under the SAME environment."
    echo "  The ICD is fine; the problem is in the JVM binding. See the JOCL"
    echo "  section of the report for the cause chain."
fi

echo
echo "Full report: ${OUT}"
echo "  less ${OUT}"
echo "  grep -n 'RESULT\\|not found\\|JOCL_FAIL' ${OUT}"
