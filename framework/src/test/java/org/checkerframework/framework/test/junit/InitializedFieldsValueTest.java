package org.checkerframework.framework.test.junit;

import org.checkerframework.framework.test.CheckerFrameworkPerDirectoryTest;
import org.junit.runners.Parameterized.Parameters;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class InitializedFieldsValueTest extends CheckerFrameworkPerDirectoryTest {

    /**
     * Create a InitializedFieldsValueTest.
     *
     * @param testFiles the files containing test code, which will be type-checked
     */
    public InitializedFieldsValueTest(List<File> testFiles) {
        super(
                testFiles,
                Arrays.asList(
                        "org.checkerframework.common.initializedfields.InitializedFieldsChecker",
                        "org.checkerframework.common.value.ValueChecker"),
                "initialized-fields-value",
                Collections.emptyList(), // classpathextra
                "-Anomsgtext",
                "-AsuppressWarnings=type.checking.not.run");
    }

    @Parameters
    public static String[] getTestDirs() {
        return new String[] {"initialized-fields-value", "all-systems"};
    }
}
