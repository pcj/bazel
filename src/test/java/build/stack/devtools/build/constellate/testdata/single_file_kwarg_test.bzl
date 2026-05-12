"""Verifies attr.label(single_file=...) doesn't abort evaluation.

`single_file` is a removed legacy kwarg. Without the deprecated-params
retry path including `single_file`, the rule() call below raises
`got unexpected keyword argument 'single_file'` and aborts the module
before any globals get bound.
"""

_before_marker = "exists"

def _impl(ctx):
    return []

legacy_rule = rule(
    implementation = _impl,
    attrs = {
        "files": attr.label(single_file = True),
    },
)
