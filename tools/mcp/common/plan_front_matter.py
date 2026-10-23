"""The lifecycle header every plan document in ``docs/plans/`` carries.

A plan starts with a front-matter block::

    ---
    status: in-progress
    created: 2026-10-23
    closed:
    tracker: [9856a34e-658f-4e82-ad5b-6d671c979f2f]
    ---

``status`` is one of :data:`STATUSES`; ``created`` is the ISO date the plan was
written; ``closed`` is the ISO date it became ``completed`` or ``abandoned``,
and is set exactly when the status is one of those two; ``tracker`` lists the
tracker task ids the plan carries.

The header exists so a finished plan can be removed without reading it: the
periodic sweep (``tools/ci/sweep-plans.py``) deletes plans whose ``closed``
date is older than the retention window. The rules are documented in
``docs/plans/CLAUDE.md``.

Exempt from the header: ``docs/plans/CLAUDE.md``, ``docs/plans/MANAGER_LOG.md``,
everything under a release-version directory (``docs/plans/<version>/``, which
goal decomposition manages), and any file that is not Markdown.
"""

import datetime
import posixpath
import re
from dataclasses import dataclass, field
from typing import List, Optional, Tuple

STATUSES = ("proposed", "approved", "in-progress", "completed", "abandoned")
CLOSED_STATUSES = ("completed", "abandoned")
PLANS_DIR = "docs/plans"
EXEMPT_NAMES = ("CLAUDE.md", "MANAGER_LOG.md")

_VERSION_DIR = re.compile(r"^\d+(?:\.\d+)+$")
_FIELD = re.compile(r"^([a-z]+):\s*(.*?)\s*$")
_FIELDS = ("status", "created", "closed", "tracker")


def is_plan_path(path: str) -> bool:
    """True if a repository-relative path is a plan that must carry the header."""
    normalized = posixpath.normpath(path)
    if not normalized.startswith(PLANS_DIR + "/") or not normalized.endswith(".md"):
        return False
    parts = normalized[len(PLANS_DIR) + 1:].split("/")
    if len(parts) == 1 and parts[0] in EXEMPT_NAMES:
        return False
    return not (len(parts) > 1 and _VERSION_DIR.match(parts[0]))


@dataclass
class PlanFrontMatter:
    """A parsed plan header."""

    status: str
    created: Optional[datetime.date]
    closed: Optional[datetime.date] = None
    tracker: List[str] = field(default_factory=list)

    @classmethod
    def new(cls, today: datetime.date, tracker: Optional[List[str]] = None) -> "PlanFrontMatter":
        """The header for a plan written today and not yet approved."""
        return cls(status="proposed", created=today, tracker=list(tracker or []))

    @classmethod
    def parse(cls, text: str) -> Tuple[Optional["PlanFrontMatter"], List[str]]:
        """Parse the header at the top of a plan.

        Returns the header (``None`` when there is no usable block) and the
        list of problems found; a header is valid when that list is empty.
        """
        lines = text.lstrip("\ufeff").splitlines()
        if not lines or lines[0].strip() != "---":
            return None, ["missing front-matter block (the plan must start with '---')"]
        try:
            end = next(i for i in range(1, len(lines)) if lines[i].strip() == "---")
        except StopIteration:
            return None, ["front-matter block is not closed with '---'"]

        values = {}
        problems = []
        for line in lines[1:end]:
            if not line.strip():
                continue
            match = _FIELD.match(line)
            if match is None or match.group(1) not in _FIELDS:
                problems.append("unrecognised front-matter line: %r" % line)
                continue
            values[match.group(1)] = match.group(2)

        created, problem = _parse_date(values.get("created", ""), "created", required=True)
        problems.extend(problem)
        closed, problem = _parse_date(values.get("closed", ""), "closed", required=False)
        problems.extend(problem)
        tracker, problem = _parse_tracker(values.get("tracker", "[]"))
        problems.extend(problem)

        header = cls(status=values.get("status", ""), created=created, closed=closed, tracker=tracker)
        problems.extend(header.problems())
        return header, problems

    def problems(self) -> List[str]:
        """Consistency problems between the fields."""
        if self.status not in STATUSES:
            return ["status %r is not one of %s" % (self.status, ", ".join(STATUSES))]
        if self.status in CLOSED_STATUSES and self.closed is None:
            return ["status %r requires a closed date" % self.status]
        if self.status not in CLOSED_STATUSES and self.closed is not None:
            return ["status %r must not have a closed date" % self.status]
        if self.closed is not None and self.created is not None and self.closed < self.created:
            return ["closed date precedes created date"]
        return []

    def render(self) -> str:
        """The header as it is written at the top of a plan."""
        return "---\nstatus: %s\ncreated: %s\nclosed: %s\ntracker: [%s]\n---\n" % (
            self.status,
            self.created.isoformat() if self.created else "",
            self.closed.isoformat() if self.closed else "",
            ", ".join(self.tracker),
        )

    def is_expired(self, today: datetime.date, retention_days: int) -> bool:
        """True if the plan was closed more than ``retention_days`` before today."""
        return self.closed is not None and (today - self.closed).days > retention_days


def with_header(text: str, today: datetime.date) -> Tuple[str, List[str]]:
    """Return a plan's text with a valid header, and the problems preventing one.

    Text with no header gets a new ``proposed`` header dated today. Text that
    already has one is returned unchanged with whatever problems it has. A
    leading byte-order mark is dropped when a header is added, so it does not
    end up in the middle of the file in front of the plan's first line.
    """
    body = text.lstrip("\ufeff")
    if not body.startswith("---"):
        return PlanFrontMatter.new(today).render() + "\n" + body, []
    _, problems = PlanFrontMatter.parse(text)
    return text, problems


def _parse_date(value: str, name: str, required: bool):
    if not value:
        return None, (["%s date is required" % name] if required else [])
    try:
        return datetime.date.fromisoformat(value), []
    except ValueError:
        return None, ["%s %r is not an ISO date (YYYY-MM-DD)" % (name, value)]


def _parse_tracker(value: str):
    if not (value.startswith("[") and value.endswith("]")):
        return [], ["tracker %r must be a list such as [] or [id, id]" % value]
    return [item.strip() for item in value[1:-1].split(",") if item.strip()], []
