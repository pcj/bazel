"""Verifies that select({}) at top-level evaluates without throwing.

The fake select() returns a FakeDeepStructure on empty dict so downstream
code keeps evaluating. Without this, `Error in select: select: empty dict`
would abort module evaluation.
"""

_x = select({})

def some_function(name):
    """A function exported alongside the empty-select binding."""
    pass
