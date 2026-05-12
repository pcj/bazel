"""Verifies that two globals aliased to the same rule callable don't crash.

Both `_my_rule` and `my_rule` point at the same rule() callable. resolveGlobals
iterates both bindings and tries to register the same `rule_name` twice, which
without buildKeepingLast() throws "Multiple entries with same key" at the
final `ruleInfoMap.build()` call.
"""

def _impl(ctx):
    return []

_my_rule = rule(
    implementation = _impl,
    doc = "An aliased rule.",
)

# Public alias of the same underlying callable.
my_rule = _my_rule
