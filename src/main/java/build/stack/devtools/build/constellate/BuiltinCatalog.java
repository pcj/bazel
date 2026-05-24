// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.stack.devtools.build.constellate;

import build.stack.starlark.v1beta1.StarlarkProtos.BuiltinInfoResponse;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.docgen.builtin.BuiltinProtos;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos;
import com.google.protobuf.ExtensionRegistry;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bundled API catalog: the Bazel binary's {@code builtin.pb} plus the six
 * {@code gen_be_*_stardoc_proto} outputs, parsed once and cached for the JVM
 * lifetime.
 *
 * <p>The data is produced at build time by
 * {@code //src/main/java/com/google/devtools/build/lib:gen_api_proto} (for
 * {@code builtin.pb}) and {@code //src/main/starlark/docgen:gen_be_*} (for
 * the per-language stardoc protos). Both are bundled as classpath resources
 * on {@code evaluator_lib} so this class can read them via
 * {@link Class#getResourceAsStream}.
 */
final class BuiltinCatalog {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * JAR classpath path of the {@code gen_api_proto} output. Because the
   * genrule lives under {@code src/main/java/...}, Bazel applies the
   * standard Java-resource strip (drops {@code src/main/java/}), so the
   * file is exposed at the Java-package-relative path rather than the
   * workspace-relative path used for the stardoc resources below.
   */
  private static final String BUILTINS_RESOURCE =
      "/com/google/devtools/build/lib/builtin.pb";

  /**
   * The six per-language stardoc proto resources. Order is preserved in the
   * response's {@code module_info} / {@code module_info_source} fields so a
   * consumer can correlate index → language.
   */
  private static final String[] STARDOC_RESOURCES = {
      "/main/starlark/docgen/gen_be_cpp_stardoc_proto.binaryproto",
      "/main/starlark/docgen/gen_be_java_stardoc_proto.binaryproto",
      "/main/starlark/docgen/gen_be_objc_stardoc_proto.binaryproto",
      "/main/starlark/docgen/gen_be_proto_stardoc_proto.binaryproto",
      "/main/starlark/docgen/gen_be_python_stardoc_proto.binaryproto",
      "/main/starlark/docgen/gen_be_shell_stardoc_proto.binaryproto",
  };

  /** Parallel to {@link #STARDOC_RESOURCES}, the short label for each. */
  private static final String[] STARDOC_SOURCES = {
      "cpp", "java", "objc", "proto", "python", "shell",
  };

  /** Built once at class init and reused — the proto messages are immutable. */
  private static final BuiltinInfoResponse CATALOG = load();

  private BuiltinCatalog() {}

  /** Returns the cached catalog. */
  static BuiltinInfoResponse get() {
    return CATALOG;
  }

  private static BuiltinInfoResponse load() {
    BuiltinInfoResponse.Builder out = BuiltinInfoResponse.newBuilder();

    // 1) builtin.pb — types, globals, native rules.
    try (InputStream stream = BuiltinCatalog.class.getResourceAsStream(BUILTINS_RESOURCE)) {
      if (stream == null) {
        logger.atWarning().log("Builtins resource not found: %s (proceeding without)", BUILTINS_RESOURCE);
      } else {
        out.setBuiltins(
            BuiltinProtos.Builtins.parseFrom(stream, ExtensionRegistry.getEmptyRegistry()));
      }
    } catch (IOException e) {
      logger.atWarning().withCause(e).log("Failed to parse %s", BUILTINS_RESOURCE);
    }

    // 2) Six stardoc_output.ModuleInfo protos.
    for (int i = 0; i < STARDOC_RESOURCES.length; i++) {
      String path = STARDOC_RESOURCES[i];
      String source = STARDOC_SOURCES[i];
      try (InputStream stream = BuiltinCatalog.class.getResourceAsStream(path)) {
        if (stream == null) {
          logger.atWarning().log("Stardoc resource not found: %s (skipping)", path);
          continue;
        }
        StardocOutputProtos.ModuleInfo moduleInfo =
            StardocOutputProtos.ModuleInfo.parseFrom(stream, ExtensionRegistry.getEmptyRegistry());
        out.addModuleInfo(moduleInfo);
        out.addModuleInfoSource(source);
      } catch (IOException e) {
        logger.atWarning().withCause(e).log("Failed to parse %s", path);
      }
    }

    return out.build();
  }
}
