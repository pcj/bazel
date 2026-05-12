"""Verifies that a top-level file with unresolvable loads is soft-failed.

The retry loop in StarlarkEvaluator caps at MAX_RETRIES_PER_FILE = 10 stub
substitutions per file. By loading 11 symbols that don't exist in
load_test_lib.bzl, we force the retry to exhaust on the 11th iteration with
its `lastException` still pointing at a `does not contain symbol` error.

Before Tier 2.C, the post-loop fallback only swallowed that error for
transitive loads, not for the top-level entry file — eval() rethrew. After
the fix, the top-level case is also swallowed and eval() returns a partial
module.
"""

load(
    "load_test_lib.bzl",
    "Missing01",
    "Missing02",
    "Missing03",
    "Missing04",
    "Missing05",
    "Missing06",
    "Missing07",
    "Missing08",
    "Missing09",
    "Missing10",
    "Missing11",
)

def some_function(name):
    """Survives partial extraction even when the load exhausted retries."""
    pass
