package build.stack.devtools.build.constellate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import build.stack.starlark.v1beta1.StarlarkProtos.LoadStmt;
import build.stack.starlark.v1beta1.StarlarkProtos.Package;
import build.stack.starlark.v1beta1.StarlarkProtos.Target;
import build.stack.starlark.v1beta1.StarlarkProtos.Value;
import com.google.devtools.build.lib.cmdline.Label;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.starlark.java.syntax.ParserInput;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for {@link BuildFileEvaluator}. AST-only extraction; no execution. */
@RunWith(JUnit4.class)
public class BuildFileEvaluatorTest {

  private static final String TEST_DATA_DIR =
      "src/test/java/build/stack/devtools/build/constellate/testdata";

  private Package evaluateBuild(String filename) throws Exception {
    Path testFile = Paths.get(TEST_DATA_DIR, filename);
    assertTrue("Test file not found: " + testFile, Files.exists(testFile));
    ParserInput input = ParserInput.fromLatin1(Files.readAllBytes(testFile), filename);
    Label label = Label.parseCanonicalUnchecked("//test:" + filename);
    BuildFileEvaluator evaluator = new BuildFileEvaluator(new FilesystemFileAccessor());
    return evaluator.eval(input, label);
  }

  @Test
  public void testSampleBuildExtraction() throws Exception {
    Package pkg = evaluateBuild("sample_BUILD.bzl");
    assertNotNull(pkg);
    assertFalse("filename should be set", pkg.getFilename().isEmpty());

    // ----- load statements -----
    assertEquals(1, pkg.getLoadCount());
    LoadStmt load = pkg.getLoad(0);
    assertEquals("load_test_lib.bzl", load.getLabel().getName());
    assertEquals(2, load.getSymbolCount());
    assertEquals("lib_function", load.getSymbol(0).getFrom());
    assertEquals("lib_rule", load.getSymbol(1).getFrom());

    // ----- package(...) declaration -----
    assertTrue("package() declaration must be present",
        pkg.hasPackageDeclaration());
    assertTrue("package() default_visibility attribute captured",
        pkg.getPackageDeclaration().containsAttribute("default_visibility"));

    // ----- top-level bindings -----
    assertTrue("LIBS binding present", pkg.containsBinding("LIBS"));
    Value libs = pkg.getBindingOrThrow("LIBS");
    assertEquals("LIBS is a list", Value.ValueCase.LIST, libs.getValueCase());
    assertEquals(3, libs.getList().getValueCount());
    assertEquals("a", libs.getList().getValue(0).getString());

    assertTrue("USE_DEBUG binding present", pkg.containsBinding("USE_DEBUG"));
    assertEquals(true, pkg.getBindingOrThrow("USE_DEBUG").getBool());

    // ----- targets in source order -----
    assertEquals("five top-level rule calls in source order", 5, pkg.getTargetCount());

    Target foo = pkg.getTarget(0);
    assertEquals("cc_library", foo.getKind());
    assertEquals("foo", foo.getName());
    assertFalse("cc_library is a native rule, not a macro", foo.getIsMacro());
    // srcs/hdrs/deps should all be captured as Attribute entries
    boolean sawSrcs = false, sawDeps = false;
    for (int i = 0; i < foo.getAttributeCount(); i++) {
      String attrName = foo.getAttribute(i).getName();
      if (attrName.equals("srcs")) {
        sawSrcs = true;
        assertEquals(Value.ValueCase.LIST, foo.getAttribute(i).getValue().getValueCase());
        assertEquals("foo.cc", foo.getAttribute(i).getValue().getList().getValue(0).getString());
      }
      if (attrName.equals("deps")) {
        // deps = LIBS — RHS is an Identifier, not statically resolvable
        // in AST-only mode, so the attribute is recorded with no value.
        sawDeps = true;
        assertFalse("deps attribute has no statically-resolvable value (it's Identifier LIBS)",
            foo.getAttribute(i).hasValue());
      }
    }
    assertTrue("srcs attribute captured", sawSrcs);
    assertTrue("deps attribute (with no value) still captured", sawDeps);

    Target main = pkg.getTarget(1);
    assertEquals("cc_binary", main.getKind());
    assertEquals("main", main.getName());
    assertFalse("cc_binary is a native rule", main.getIsMacro());

    Target fromMacro = pkg.getTarget(2);
    assertEquals("lib_rule", fromMacro.getKind());
    assertEquals("from_macro", fromMacro.getName());
    assertTrue("lib_rule was loaded from a .bzl, so is_macro=true", fromMacro.getIsMacro());

    // ----- glob() attribute captured as ValueCall -----
    Target allBzls = pkg.getTarget(3);
    assertEquals("filegroup", allBzls.getKind());
    assertEquals("all_bzls", allBzls.getName());
    Value srcs = findAttribute(allBzls, "srcs");
    assertNotNull("srcs attribute on filegroup", srcs);
    assertEquals("srcs = glob(...) captured as Call", Value.ValueCase.CALL, srcs.getValueCase());
    assertEquals("glob", srcs.getCall().getFunctionName());
    assertEquals("one positional arg (the include pattern list)", 1, srcs.getCall().getPositionalCount());
    Value globPatterns = srcs.getCall().getPositional(0);
    assertEquals(Value.ValueCase.LIST, globPatterns.getValueCase());
    assertEquals("*.bzl", globPatterns.getList().getValue(0).getString());

    // ----- select() attribute captured as ValueCall with ValueDict positional -----
    Target selectable = pkg.getTarget(4);
    assertEquals("cc_library", selectable.getKind());
    assertEquals("selectable", selectable.getName());
    Value deps = findAttribute(selectable, "deps");
    assertNotNull("deps attribute on selectable", deps);
    assertEquals(Value.ValueCase.CALL, deps.getValueCase());
    assertEquals("select", deps.getCall().getFunctionName());
    assertEquals(1, deps.getCall().getPositionalCount());
    Value selectDict = deps.getCall().getPositional(0);
    assertEquals("select's positional arg is a dict", Value.ValueCase.DICT, selectDict.getValueCase());
    assertEquals("two select branches", 2, selectDict.getDict().getEntryCount());
    assertEquals("//conditions:linux", selectDict.getDict().getEntry(0).getKey().getString());
    assertEquals("//deps:linux_extra",
        selectDict.getDict().getEntry(0).getValue().getList().getValue(0).getString());
    assertEquals("//conditions:default", selectDict.getDict().getEntry(1).getKey().getString());
    assertEquals("default branch is an empty list",
        0, selectDict.getDict().getEntry(1).getValue().getList().getValueCount());
  }

  private static Value findAttribute(Target target, String name) {
    for (int i = 0; i < target.getAttributeCount(); i++) {
      if (target.getAttribute(i).getName().equals(name)) {
        return target.getAttribute(i).getValue();
      }
    }
    return null;
  }
}
