package com.kalessil.phpStorm.phpInspectionsEA.codeStyle;

import com.kalessil.phpStorm.phpInspectionsEA.PhpCodeInsightFixtureTestCase;
import com.kalessil.phpStorm.phpInspectionsEA.inspectors.codeStyle.UnnecessaryUseAliasInspector;

final public class UnnecessaryUseAliasInspectorTest extends PhpCodeInsightFixtureTestCase {
    public void testIfFindsAllPatterns() {
        myFixture.configureByFile("fixtures/codeStyle/use-aliases.php");
        myFixture.enableInspections(UnnecessaryUseAliasInspector.class);
        myFixture.testHighlighting(true, false, true);
    }
}
