"""External system adapters with no eager runtime imports."""

from .search_provider import (
    SearchCandidate,
    SearchProviderError,
    SearchProviderUnavailable,
    SearxngSearchProvider,
    build_search_provider,
    provider_info,
)

__all__ = [
    "SearchCandidate",
    "SearchProviderError",
    "SearchProviderUnavailable",
    "SearxngSearchProvider",
    "build_search_provider",
    "provider_info",
]
