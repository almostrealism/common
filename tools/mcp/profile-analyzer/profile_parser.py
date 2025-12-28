"""
JavaBeans XML Parser for OperationProfileNode

Parses the XML format produced by Java's XMLEncoder for OperationProfileNode objects.
"""

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional


@dataclass
class TimingMetric:
    """Timing metric with entries and counts."""
    entries: dict[str, float] = field(default_factory=dict)
    counts: dict[str, int] = field(default_factory=dict)

    @property
    def total(self) -> float:
        """Total time in seconds."""
        return sum(self.entries.values())

    def get_average(self, key: str) -> float:
        """Get average time for a key."""
        if key in self.entries and key in self.counts and self.counts[key] > 0:
            return self.entries[key] / self.counts[key]
        return 0.0


@dataclass
class OperationSource:
    """Generated source code for an operation."""
    source: str = ""
    argument_keys: list[str] = field(default_factory=list)
    argument_names: list[str] = field(default_factory=list)


@dataclass
class ProfileNode:
    """Represents an OperationProfileNode from the profile XML."""
    key: str = ""
    name: str = ""
    children: list["ProfileNode"] = field(default_factory=list)
    metric: Optional[TimingMetric] = None
    measured_time: Optional[TimingMetric] = None
    stage_detail_time: Optional[TimingMetric] = None
    operation_sources: dict[str, list[OperationSource]] = field(default_factory=dict)
    metadata: dict[str, str] = field(default_factory=dict)

    # Cached computed values
    _total_duration: Optional[float] = field(default=None, repr=False)

    @property
    def self_duration(self) -> float:
        """Duration of this node only (excluding children)."""
        if self.metric:
            return self.metric.total
        return 0.0

    @property
    def children_duration(self) -> float:
        """Total duration of all children."""
        return sum(child.total_duration for child in self.children)

    @property
    def total_duration(self) -> float:
        """Total duration including children."""
        if self._total_duration is not None:
            return self._total_duration
        return self.self_duration + self.children_duration

    @property
    def measured_duration(self) -> float:
        """Measured duration if available."""
        if self.measured_time:
            return self.measured_time.total
        return 0.0

    @property
    def has_source(self) -> bool:
        """Whether this node has source code."""
        return bool(self.operation_sources.get(self.key))

    def get_source(self) -> Optional[OperationSource]:
        """Get the first source for this node."""
        sources = self.operation_sources.get(self.key, [])
        return sources[0] if sources else None

    def find_by_key(self, key: str) -> Optional["ProfileNode"]:
        """Find a node by key in this subtree."""
        if self.key == key:
            return self
        for child in self.children:
            result = child.find_by_key(key)
            if result:
                return result
        return None

    def all_nodes(self) -> list["ProfileNode"]:
        """Get all nodes in this subtree (including self)."""
        result = [self]
        for child in self.children:
            result.extend(child.all_nodes())
        return result

    def get_path(self, target_key: str, current_path: str = "") -> Optional[str]:
        """Get the path from this node to a target key."""
        my_path = f"{current_path} > {self.name}" if current_path else self.name
        if self.key == target_key:
            return my_path
        for child in self.children:
            result = child.get_path(target_key, my_path)
            if result:
                return result
        return None


class ProfileParser:
    """Parser for JavaBeans XML format used by OperationProfileNode."""

    def parse_file(self, path: str | Path) -> ProfileNode:
        """Parse a profile XML file."""
        tree = ET.parse(path)
        root = tree.getroot()

        # Find the OperationProfileNode object
        for obj in root.findall(".//object[@class='io.almostrealism.profile.OperationProfileNode']"):
            return self._parse_profile_node(obj)

        raise ValueError(f"No OperationProfileNode found in {path}")

    def _parse_profile_node(self, element: ET.Element) -> ProfileNode:
        """Parse an OperationProfileNode element."""
        node = ProfileNode()

        for void_elem in element.findall("void"):
            prop = void_elem.get("property")
            if prop == "key":
                node.key = self._get_string_value(void_elem)
            elif prop == "name":
                node.name = self._get_string_value(void_elem)
            elif prop == "children":
                node.children = self._parse_children(void_elem)
            elif prop == "metric":
                node.metric = self._parse_timing_metric(void_elem)
            elif prop == "measuredTime":
                node.measured_time = self._parse_timing_metric(void_elem)
            elif prop == "stageDetailTime":
                node.stage_detail_time = self._parse_timing_metric(void_elem)
            elif prop == "operationSources":
                node.operation_sources = self._parse_operation_sources(void_elem)
            elif prop == "metadata":
                node.metadata = self._parse_string_map(void_elem)
            elif prop == "metricEntries":
                # Alternative format for metric entries
                if node.metric is None:
                    node.metric = TimingMetric()
                node.metric.entries = self._parse_double_map(void_elem)
            elif prop == "metricCounts":
                # Alternative format for metric counts
                if node.metric is None:
                    node.metric = TimingMetric()
                node.metric.counts = self._parse_int_map(void_elem)

        # Propagate operation sources to children
        self._propagate_sources(node)

        return node

    def _propagate_sources(self, node: ProfileNode):
        """Propagate operation sources from root to all children."""
        for child in node.children:
            # Merge parent sources into child
            for key, sources in node.operation_sources.items():
                if key not in child.operation_sources:
                    child.operation_sources[key] = sources
            self._propagate_sources(child)

    def _parse_children(self, element: ET.Element) -> list[ProfileNode]:
        """Parse children list."""
        children = []

        # Find ArrayList
        for array_list in element.findall(".//object[@class='java.util.ArrayList']"):
            for void_add in array_list.findall("void[@method='add']"):
                for obj in void_add.findall("object[@class='io.almostrealism.profile.OperationProfileNode']"):
                    children.append(self._parse_profile_node(obj))

        return children

    def _parse_timing_metric(self, element: ET.Element) -> Optional[TimingMetric]:
        """Parse a TimingMetric element."""
        metric_obj = element.find(".//object[@class='org.almostrealism.io.TimingMetric']")
        if metric_obj is None:
            return None

        metric = TimingMetric()

        for void_elem in metric_obj.findall("void"):
            prop = void_elem.get("property")
            if prop == "entries":
                metric.entries = self._parse_double_map(void_elem)
            elif prop == "counts":
                metric.counts = self._parse_int_map(void_elem)

        return metric

    def _parse_operation_sources(self, element: ET.Element) -> dict[str, list[OperationSource]]:
        """Parse operation sources map."""
        result = {}

        # Find HashMap entries
        for hash_map in element.findall(".//object[@class='java.util.HashMap']"):
            for void_put in hash_map.findall("void[@method='put']"):
                children = list(void_put)
                if len(children) >= 2:
                    key = self._extract_value(children[0])
                    sources = self._parse_source_list(children[1])
                    if key and sources:
                        result[str(key)] = sources

        return result

    def _parse_source_list(self, element: ET.Element) -> list[OperationSource]:
        """Parse a list of OperationSource objects."""
        sources = []

        for array_list in element.findall(".//object[@class='java.util.ArrayList']"):
            for void_add in array_list.findall("void[@method='add']"):
                for obj in void_add.findall("object[@class='io.almostrealism.profile.OperationSource']"):
                    sources.append(self._parse_operation_source(obj))

        # Also check for direct OperationSource objects
        for obj in element.findall(".//object[@class='io.almostrealism.profile.OperationSource']"):
            sources.append(self._parse_operation_source(obj))

        return sources

    def _parse_operation_source(self, element: ET.Element) -> OperationSource:
        """Parse an OperationSource element."""
        source = OperationSource()

        for void_elem in element.findall("void"):
            prop = void_elem.get("property")
            if prop == "source":
                source.source = self._get_string_value(void_elem)
            elif prop == "argumentKeys":
                source.argument_keys = self._parse_string_list(void_elem)
            elif prop == "argumentNames":
                source.argument_names = self._parse_string_list(void_elem)

        return source

    def _parse_string_list(self, element: ET.Element) -> list[str]:
        """Parse a list of strings."""
        result = []

        for array_list in element.findall(".//object[@class='java.util.ArrayList']"):
            for void_add in array_list.findall("void[@method='add']"):
                value = self._get_string_value(void_add)
                if value:
                    result.append(value)

        return result

    def _parse_string_map(self, element: ET.Element) -> dict[str, str]:
        """Parse a map of string to string."""
        result = {}

        for hash_map in element.findall(".//object[@class='java.util.HashMap']"):
            for void_put in hash_map.findall("void[@method='put']"):
                children = list(void_put)
                if len(children) >= 2:
                    key = self._extract_value(children[0])
                    value = self._extract_value(children[1])
                    if key is not None:
                        result[str(key)] = str(value) if value else ""

        return result

    def _parse_double_map(self, element: ET.Element) -> dict[str, float]:
        """Parse a map of string to double."""
        result = {}

        # Handle both direct HashMap and Collections.synchronizedMap wrapper
        for hash_map in element.findall(".//object[@class='java.util.HashMap']"):
            for void_put in hash_map.findall("void[@method='put']"):
                children = list(void_put)
                if len(children) >= 2:
                    key = self._extract_value(children[0])
                    value = self._extract_value(children[1])
                    if key is not None and value is not None:
                        try:
                            result[str(key)] = float(value)
                        except (ValueError, TypeError):
                            pass

        return result

    def _parse_int_map(self, element: ET.Element) -> dict[str, int]:
        """Parse a map of string to int."""
        result = {}

        for hash_map in element.findall(".//object[@class='java.util.HashMap']"):
            for void_put in hash_map.findall("void[@method='put']"):
                children = list(void_put)
                if len(children) >= 2:
                    key = self._extract_value(children[0])
                    value = self._extract_value(children[1])
                    if key is not None and value is not None:
                        try:
                            result[str(key)] = int(value)
                        except (ValueError, TypeError):
                            pass

        return result

    def _get_string_value(self, element: ET.Element) -> str:
        """Get string value from a void property element."""
        string_elem = element.find("string")
        if string_elem is not None and string_elem.text:
            return string_elem.text
        return ""

    def _extract_value(self, element: ET.Element):
        """Extract value from various element types."""
        if element.tag == "string":
            return element.text or ""
        elif element.tag == "double":
            return float(element.text) if element.text else 0.0
        elif element.tag == "int":
            return int(element.text) if element.text else 0
        elif element.tag == "float":
            return float(element.text) if element.text else 0.0
        elif element.tag == "long":
            return int(element.text) if element.text else 0
        elif element.tag == "boolean":
            return element.text.lower() == "true" if element.text else False

        # Check for nested value
        for child in element:
            result = self._extract_value(child)
            if result is not None:
                return result

        return None
