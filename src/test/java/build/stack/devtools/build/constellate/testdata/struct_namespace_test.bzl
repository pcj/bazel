"""Namespace module that re-exports private helpers via a public struct."""

def _basename(p):
    return p.rsplit("/", 1)[-1]

def _dirname(p):
    return p.rsplit("/", 1)[0]

def _join(*paths):
    return "/".join(paths)

paths = struct(
    basename = _basename,
    dirname = _dirname,
    join = _join,
)
