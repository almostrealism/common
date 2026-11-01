"""Runs the bioscience conversion-script tests as part of this suite.

The MaleCNS conversion scripts and their unittest live with the module they
serve, under studio/bioscience/scripts. CI's python-tests job discovers tests
only under tools/tests (and a few other fixed directories), so this module
imports those test cases into its own namespace, where discovery collects them.
The MaleCNS tests need only the standard library; the connectome conversion and
Brian2 reference tests need numpy, which the job installs from
tools/mcp/requirements.txt. Brian2 itself is imported only inside the functions
that run it, so these tests do not need it.
"""

import os
import sys

_SCRIPTS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                        os.pardir, os.pardir, "studio", "bioscience", "scripts")
sys.path.insert(0, os.path.normpath(_SCRIPTS))

from test_connectome_csr import (  # noqa: E402,F401
    CompressedRowsTest,
    EventsTest,
    KickScheduleTest,
    SafetensorsRoundTripTest,
    SubsetTest,
    SyntheticConversionTest,
)
from test_male_cns import (  # noqa: E402,F401
    MajorityAnnotationsTest,
    SafetensorsHeaderTest,
    SourceChecksumsTest,
    TransmitterSignTest,
)
