# NOTE: Stored as sample_BUILD.bzl (not BUILD) so the filename pattern
# matches the .bzl testdata glob and the file gets distributed with tests.
# BuildFileEvaluator doesn't care about the filename — only about the syntax
# being BUILD-file shaped.

load("load_test_lib.bzl", "lib_function", "lib_rule")

package(default_visibility = ["//visibility:public"])

LIBS = [
    "a",
    "b",
    "c",
]

USE_DEBUG = True

cc_library(
    name = "foo",
    srcs = ["foo.cc"],
    hdrs = ["foo.h"],
    deps = LIBS,
)

cc_binary(
    name = "main",
    srcs = ["main.cc"],
    deps = [":foo"],
)

lib_rule(
    name = "from_macro",
    value = "x",
)
