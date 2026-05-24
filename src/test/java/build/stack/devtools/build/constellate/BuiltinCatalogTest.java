package build.stack.devtools.build.constellate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import build.stack.starlark.v1beta1.StarlarkProtos.BuiltinInfoResponse;
import com.google.devtools.build.docgen.builtin.BuiltinProtos;
import com.google.devtools.build.lib.starlarkdocextract.StardocOutputProtos;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests that {@link BuiltinCatalog} loads the bundled API catalog resources correctly. */
@RunWith(JUnit4.class)
public class BuiltinCatalogTest {

  @Test
  public void testCatalogIsNonEmpty() {
    BuiltinInfoResponse catalog = BuiltinCatalog.get();
    assertNotNull(catalog);

    // builtins.pb should be present with types and globals.
    assertTrue("builtins.pb should be embedded", catalog.hasBuiltins());
    BuiltinProtos.Builtins builtins = catalog.getBuiltins();
    assertTrue("builtins should expose many types, got " + builtins.getTypeCount(),
        builtins.getTypeCount() > 10);
    assertTrue("builtins should expose many globals, got " + builtins.getGlobalCount(),
        builtins.getGlobalCount() > 10);
  }

  @Test
  public void testWellKnownTypesPresent() {
    BuiltinProtos.Builtins builtins = BuiltinCatalog.get().getBuiltins();
    boolean sawDepset = false;
    boolean sawDict = false;
    for (int i = 0; i < builtins.getTypeCount(); i++) {
      String name = builtins.getType(i).getName();
      if (name.equals("depset")) sawDepset = true;
      if (name.equals("dict")) sawDict = true;
    }
    assertTrue("expected 'depset' in builtins.type", sawDepset);
    assertTrue("expected 'dict' in builtins.type", sawDict);
  }

  @Test
  public void testWellKnownGlobalsPresent() {
    BuiltinProtos.Builtins builtins = BuiltinCatalog.get().getBuiltins();
    boolean sawSelect = false;
    boolean sawGlob = false;
    for (int i = 0; i < builtins.getGlobalCount(); i++) {
      String name = builtins.getGlobal(i).getName();
      if (name.equals("select")) sawSelect = true;
      if (name.equals("glob")) sawGlob = true;
    }
    assertTrue("expected 'select' in builtins.global", sawSelect);
    assertTrue("expected 'glob' in builtins.global", sawGlob);
  }

  @Test
  public void testSixStardocModulesLoaded() {
    BuiltinInfoResponse catalog = BuiltinCatalog.get();
    assertEquals("expected 6 ModuleInfo entries", 6, catalog.getModuleInfoCount());
    assertEquals("module_info_source must be parallel to module_info",
        catalog.getModuleInfoCount(), catalog.getModuleInfoSourceCount());

    // Sources are in fixed order: cpp, java, objc, proto, python, shell.
    assertEquals("cpp", catalog.getModuleInfoSource(0));
    assertEquals("java", catalog.getModuleInfoSource(1));
    assertEquals("objc", catalog.getModuleInfoSource(2));
    assertEquals("proto", catalog.getModuleInfoSource(3));
    assertEquals("python", catalog.getModuleInfoSource(4));
    assertEquals("shell", catalog.getModuleInfoSource(5));
  }

  @Test
  public void testStardocRetainsAttributeDetail() {
    // The whole point of bundling stardoc protos alongside builtin.pb is that
    // they preserve attribute detail (doc, type, default) that builtin.pb's
    // collectRuleInfo drops. Verify at least one rule with detail-laden attrs.
    BuiltinInfoResponse catalog = BuiltinCatalog.get();
    boolean sawAttrWithDoc = false;
    boolean sawAttrWithDefault = false;
    outer:
    for (int m = 0; m < catalog.getModuleInfoCount(); m++) {
      StardocOutputProtos.ModuleInfo module = catalog.getModuleInfo(m);
      for (int r = 0; r < module.getRuleInfoCount(); r++) {
        StardocOutputProtos.RuleInfo rule = module.getRuleInfo(r);
        for (int a = 0; a < rule.getAttributeCount(); a++) {
          StardocOutputProtos.AttributeInfo attr = rule.getAttribute(a);
          if (!attr.getDocString().isEmpty()) {
            sawAttrWithDoc = true;
          }
          if (!attr.getDefaultValue().isEmpty()) {
            sawAttrWithDefault = true;
          }
          if (sawAttrWithDoc && sawAttrWithDefault) {
            break outer;
          }
        }
      }
    }
    assertTrue("at least one stardoc AttributeInfo should carry a doc_string", sawAttrWithDoc);
    assertTrue("at least one stardoc AttributeInfo should carry a default_value", sawAttrWithDefault);
  }
}
