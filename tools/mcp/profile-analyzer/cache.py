"""
Profile Cache with LRU Eviction

Manages loaded profiles in memory with configurable cache size.
"""

import os
import uuid
from collections import OrderedDict
from dataclasses import dataclass
from typing import Optional

from profile_parser import ProfileNode, ProfileParser


@dataclass
class CachedProfile:
    """A cached profile with metadata."""
    profile_id: str
    name: str
    path: str
    root: ProfileNode
    node_count: int

    def __post_init__(self):
        """Compute node count after initialization."""
        if self.node_count == 0:
            self.node_count = len(self.root.all_nodes())


class ProfileCache:
    """LRU cache for loaded profiles."""

    def __init__(self, max_size: int = 10):
        """
        Initialize the cache.

        Args:
            max_size: Maximum number of profiles to cache.
        """
        self.max_size = max_size
        self._cache: OrderedDict[str, CachedProfile] = OrderedDict()
        self._parser = ProfileParser()

    def load(self, path: str) -> CachedProfile:
        """
        Load a profile from file, using cache if available.

        Args:
            path: Path to the profile XML file.

        Returns:
            CachedProfile with the loaded data.
        """
        # Check if already cached by path
        for profile_id, cached in self._cache.items():
            if cached.path == path:
                # Move to end (most recently used)
                self._cache.move_to_end(profile_id)
                return cached

        # Parse the file
        root = self._parser.parse_file(path)

        # Generate unique ID
        profile_id = uuid.uuid4().hex[:8]

        # Extract name from filename
        name = os.path.splitext(os.path.basename(path))[0]

        # Create cached profile
        cached = CachedProfile(
            profile_id=profile_id,
            name=name,
            path=path,
            root=root,
            node_count=0  # Will be computed in __post_init__
        )

        # Evict if necessary
        while len(self._cache) >= self.max_size:
            self._cache.popitem(last=False)  # Remove oldest

        # Add to cache
        self._cache[profile_id] = cached

        return cached

    def get(self, profile_id: str) -> Optional[CachedProfile]:
        """
        Get a cached profile by ID.

        Args:
            profile_id: The profile ID returned from load().

        Returns:
            CachedProfile if found, None otherwise.
        """
        if profile_id in self._cache:
            # Move to end (most recently used)
            self._cache.move_to_end(profile_id)
            return self._cache[profile_id]
        return None

    def remove(self, profile_id: str) -> bool:
        """
        Remove a profile from the cache.

        Args:
            profile_id: The profile ID to remove.

        Returns:
            True if removed, False if not found.
        """
        if profile_id in self._cache:
            del self._cache[profile_id]
            return True
        return False

    def clear(self):
        """Clear all cached profiles."""
        self._cache.clear()

    @property
    def size(self) -> int:
        """Current number of cached profiles."""
        return len(self._cache)

    def list_cached(self) -> list[dict]:
        """
        List all cached profiles.

        Returns:
            List of profile info dictionaries.
        """
        return [
            {
                "profile_id": cached.profile_id,
                "name": cached.name,
                "path": cached.path,
                "node_count": cached.node_count
            }
            for cached in self._cache.values()
        ]
