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

import build.stack.starlark.v1beta1.StarlarkProtos;
import com.google.common.collect.ImmutableList;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.starlark.java.syntax.Argument;
import net.starlark.java.syntax.AssignmentStatement;
import net.starlark.java.syntax.CallExpression;
import net.starlark.java.syntax.DictExpression;
import net.starlark.java.syntax.Expression;
import net.starlark.java.syntax.ExpressionStatement;
import net.starlark.java.syntax.FileOptions;
import net.starlark.java.syntax.Identifier;
import net.starlark.java.syntax.IntLiteral;
import net.starlark.java.syntax.ListExpression;
import net.starlark.java.syntax.LoadStatement;
import net.starlark.java.syntax.Location;
import net.starlark.java.syntax.ParserInput;
import net.starlark.java.syntax.StarlarkFile;
import net.starlark.java.syntax.Statement;
import net.starlark.java.syntax.StringLiteral;

/**
 * AST-based extractor for BUILD / BUILD.bazel files.
 *
 * <p>Unlike {@link StarlarkEvaluator}, which interprets a .bzl file to produce a
 * {@code Module} proto of rule <em>definitions</em>, this class statically walks a
 * BUILD file's syntax tree and produces a {@code Package} proto of rule
 * <em>instances</em> (targets) plus load statements, package metadata, and
 * top-level value bindings.
 *
 * <p>This is intentionally execution-free for v1: every kwarg is captured from
 * the AST as a best-effort {@code Value}. Complex expressions (function calls,
 * comprehensions, lambdas) yield no Value but the attribute name and location
 * are still recorded so a UI can render them as opaque source. Macros loaded
 * from .bzl files are recorded by call site only (the user chose opaque macro
 * tracking in the plan).
 *
 * <p>Phase 2 (not in this change) would optionally execute the file against a
 * fake build API for runtime-resolved attribute values, mirroring the retry/
 * soft-fail policy in {@link StarlarkEvaluator}.
 */
public final class BuildFileEvaluator {

  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /** BUILD-file syntax options per Bazel's {@code PackageFunction}. */
  private static final FileOptions BUILD_FILE_OPTIONS = FileOptions.builder()
      .requireLoadStatementsFirst(false)
      .loadBindsGlobally(true)
      .allowToplevelRebinding(true)
      .build();

  private final StarlarkFileAccessor fileAccessor;

  public BuildFileEvaluator(StarlarkFileAccessor fileAccessor) {
    this.fileAccessor = fileAccessor;
  }

  /**
   * Parses the given BUILD file and produces a {@code Package} proto. Returns
   * a partial proto if the file has syntax errors or unresolvable references;
   * errors are surfaced via the proto's {@code error} field rather than thrown.
   */
  public StarlarkProtos.Package eval(ParserInput input, Label label) {
    StarlarkProtos.Package.Builder pkg = StarlarkProtos.Package.newBuilder()
        .setName(packageDisplayName(label))
        .setFilename(input.getFile());

    StarlarkFile file;
    try {
      file = StarlarkFile.parse(input, BUILD_FILE_OPTIONS);
    } catch (Exception ex) {
      pkg.addError("parse failed: " + ex.getMessage());
      return pkg.build();
    }
    if (!file.ok()) {
      // The parser produced an AST but with errors. Surface them; still try to
      // extract whatever structure we can from the partial tree.
      for (Object ev : file.errors()) {
        pkg.addError(ev.toString());
      }
    }

    // 1) Collect symbols introduced by load() statements — used to tag
    //    subsequent top-level calls as is_macro.
    Set<String> loadedNames = new HashSet<>();
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof LoadStatement) {
        LoadStatement ls = (LoadStatement) stmt;
        StarlarkProtos.LoadStmt protoLoad = buildLoadStmt(ls, label);
        pkg.addLoad(protoLoad);
        pkg.addSymbolLocation(protoLoad.getLocation());
        for (LoadStatement.Binding binding : ls.getBindings()) {
          loadedNames.add(binding.getLocalName().getName());
        }
      }
    }

    // 2) Walk top-level call statements (rule invocations, package(), licenses())
    //    and top-level assignments (LIBS = [...]).
    for (Statement stmt : file.getStatements()) {
      if (stmt instanceof ExpressionStatement) {
        Expression expr = ((ExpressionStatement) stmt).getExpression();
        if (expr instanceof CallExpression) {
          handleTopLevelCall(pkg, (CallExpression) expr, loadedNames);
        }
      } else if (stmt instanceof AssignmentStatement) {
        handleTopLevelAssignment(pkg, (AssignmentStatement) stmt);
      }
      // BUILD files reject def/for/if at top level per
      // DotBazelFileSyntaxChecker, so we don't need to handle those.
    }

    return pkg.build();
  }

  private void handleTopLevelCall(
      StarlarkProtos.Package.Builder pkg,
      CallExpression call,
      Set<String> loadedNames) {
    Expression callee = call.getFunction();
    if (!(callee instanceof Identifier)) {
      // BUILD files don't typically have dotted calls at top level, but the
      // existing native.* legacy form (`native.cc_library(...)`) is allowed.
      // Skip silently — those are not standard BUILD authorship.
      return;
    }
    String name = ((Identifier) callee).getName();

    // Special-case the package(...) declaration.
    if ("package".equals(name)) {
      StarlarkProtos.PackageDeclaration.Builder decl = StarlarkProtos.PackageDeclaration.newBuilder()
          .setLocation(symbolLocation(name, call.getStartLocation()));
      for (Argument arg : call.getArguments()) {
        if (!(arg instanceof Argument.Keyword)) {
          continue;
        }
        Argument.Keyword kw = (Argument.Keyword) arg;
        StarlarkProtos.Value value = expressionToValue(kw.getValue());
        if (value != null) {
          decl.putAttribute(kw.getName(), value);
        }
      }
      pkg.setPackageDeclaration(decl.build());
      pkg.addSymbolLocation(decl.getLocation());
      return;
    }

    // Every other top-level call is a target: a native rule, a rule()-defined
    // callable loaded from a .bzl, a macro loaded from a .bzl, or a BUILD-only
    // helper like exports_files / licenses / package_group.
    StarlarkProtos.Target.Builder target = StarlarkProtos.Target.newBuilder()
        .setRule(name)
        .setIsMacro(loadedNames.contains(name))
        .setLocation(symbolLocation(name, call.getStartLocation()));

    for (Argument arg : call.getArguments()) {
      if (!(arg instanceof Argument.Keyword)) {
        // *args / **kwargs at top-level call sites are forbidden by the BUILD
        // syntax checker; positional args are rare for rules. Skip.
        continue;
      }
      Argument.Keyword kw = (Argument.Keyword) arg;
      String attrName = kw.getName();
      StarlarkProtos.Value value = expressionToValue(kw.getValue());
      StarlarkProtos.SymbolLocation attrLoc =
          symbolLocation(attrName, kw.getIdentifier().getStartLocation());
      StarlarkProtos.TargetAttribute.Builder attrBuilder = StarlarkProtos.TargetAttribute.newBuilder()
          .setName(attrName)
          .setLocation(attrLoc);
      if (value != null) {
        attrBuilder.setValue(value);
      }
      target.addAttribute(attrBuilder.build());

      // Capture the target's name attribute on the Target proto for
      // discoverability — most consumers expect this top-level.
      if ("name".equals(attrName) && value != null && !value.getString().isEmpty()) {
        target.setName(value.getString());
      }
    }
    pkg.addTarget(target.build());
    pkg.addSymbolLocation(target.getLocation());
  }

  private void handleTopLevelAssignment(
      StarlarkProtos.Package.Builder pkg,
      AssignmentStatement assign) {
    if (assign.getOperator() != null) {
      return; // augmented assignment like += isn't a fresh binding
    }
    Expression lhs = assign.getLHS();
    if (!(lhs instanceof Identifier)) {
      return; // skip tuple/list LHS destructuring
    }
    String name = ((Identifier) lhs).getName();
    if (name.startsWith("_")) {
      return; // private binding, not user-facing
    }
    StarlarkProtos.Value value = expressionToValue(assign.getRHS());
    if (value == null) {
      return;
    }
    // Attach location to the Value via its SymbolLocation field.
    StarlarkProtos.Value located = value.toBuilder()
        .setLocation(symbolLocation(name, assign.getStartLocation()))
        .build();
    pkg.putBinding(name, located);
    pkg.addSymbolLocation(located.getLocation());
  }

  /**
   * Best-effort AST-only conversion of a Starlark expression to a proto
   * {@link StarlarkProtos.Value}. Returns null for expressions that can't
   * be statically resolved (function calls, identifiers, comprehensions, …).
   */
  private StarlarkProtos.Value expressionToValue(Expression expr) {
    if (expr instanceof StringLiteral) {
      return StarlarkProtos.Value.newBuilder()
          .setString(((StringLiteral) expr).getValue())
          .build();
    }
    if (expr instanceof IntLiteral) {
      Number raw = ((IntLiteral) expr).getValue();
      if (raw instanceof Integer || raw instanceof Long) {
        return StarlarkProtos.Value.newBuilder().setInt(raw.longValue()).build();
      }
      // BigIntegers from very large literals — skip rather than truncate.
      return null;
    }
    if (expr instanceof Identifier) {
      // True / False are parsed as identifiers in this Starlark dialect.
      String n = ((Identifier) expr).getName();
      if ("True".equals(n)) {
        return StarlarkProtos.Value.newBuilder().setBool(true).build();
      }
      if ("False".equals(n)) {
        return StarlarkProtos.Value.newBuilder().setBool(false).build();
      }
      return null; // unresolvable in AST-only mode
    }
    if (expr instanceof ListExpression) {
      ListExpression list = (ListExpression) expr;
      StarlarkProtos.ValueList.Builder lb = StarlarkProtos.ValueList.newBuilder();
      for (Expression elem : list.getElements()) {
        StarlarkProtos.Value v = expressionToValue(elem);
        if (v != null) {
          lb.addValue(v);
        }
        // If an element doesn't convert, omit it. The Value's presence still
        // signals "this is a list with at least these literal entries".
      }
      return StarlarkProtos.Value.newBuilder().setList(lb.build()).build();
    }
    if (expr instanceof DictExpression) {
      // No ValueDict variant in the proto today; return null so the field is
      // omitted from the attribute. Adding ValueDict is tracked separately.
      return null;
    }
    // CallExpression (e.g., `select({...})`, `glob(...)`), comprehensions,
    // BinaryOperatorExpression, lambdas — all unresolvable at AST level.
    return null;
  }

  private StarlarkProtos.LoadStmt buildLoadStmt(LoadStatement ls, Label entryLabel) {
    StarlarkProtos.LoadStmt.Builder lb = StarlarkProtos.LoadStmt.newBuilder();
    String loadLabelStr = ls.getImport().getValue();
    try {
      Label parsed;
      if (loadLabelStr.startsWith("//") || loadLabelStr.startsWith("@")) {
        parsed = Label.parseCanonical(loadLabelStr);
      } else {
        parsed = Label.parseCanonical(
            "//" + entryLabel.getPackageName() + ":" + loadLabelStr);
      }
      lb.setLabel(StarlarkProtos.Label.newBuilder()
          .setRepo(parsed.getRepository().getName())
          .setPkg(parsed.getPackageName())
          .setName(parsed.getName())
          .build());
    } catch (LabelSyntaxException e) {
      // Fall back to the literal text in the name field — same behavior as
      // StarlarkEvaluator.eval() when a load label is malformed.
      lb.setLabel(StarlarkProtos.Label.newBuilder().setName(loadLabelStr).build());
    }
    lb.setLocation(symbolLocation("load", ls.getStartLocation()));
    for (LoadStatement.Binding binding : ls.getBindings()) {
      Location bLoc = binding.getLocalName().getStartLocation();
      Location bEnd = binding.getLocalName().getEndLocation();
      lb.addSymbol(StarlarkProtos.LoadSymbol.newBuilder()
          .setFrom(binding.getOriginalName().getName())
          .setTo(binding.getLocalName().getName())
          .setLocation(StarlarkProtos.SymbolLocation.newBuilder()
              .setName(binding.getLocalName().getName())
              .setStart(position(bLoc))
              .setEnd(position(bEnd))
              .build())
          .build());
    }
    return lb.build();
  }

  private static StarlarkProtos.SymbolLocation symbolLocation(String name, Location loc) {
    StarlarkProtos.Position p = position(loc);
    return StarlarkProtos.SymbolLocation.newBuilder()
        .setName(name)
        .setStart(p)
        .setEnd(p)
        .build();
  }

  private static StarlarkProtos.Position position(Location loc) {
    return StarlarkProtos.Position.newBuilder()
        .setLine(loc.line())
        .setCharacter(loc.column())
        .build();
  }

  private static String packageDisplayName(Label label) {
    // Mirror "@@repo//pkg" style without the target — useful for IDE display.
    String repo = label.getRepository().getName();
    String pkg = label.getPackageName();
    if (repo.isEmpty()) {
      return "//" + pkg;
    }
    return "@@" + repo + "//" + pkg;
  }
}
