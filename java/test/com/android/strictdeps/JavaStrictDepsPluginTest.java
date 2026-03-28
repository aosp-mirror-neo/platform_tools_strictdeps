/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.strictdeps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaCompiler.CompilationTask;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

public class JavaStrictDepsPluginTest {
    private static final String DEFAULT_SOURCE =
            "import com.example.DummyDependency; public class TestClass { DummyDependency d; }";

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

    private JavaCompiler compiler;
    private File dummyJar;

    @Before
    public void setUp() throws Exception {
        compiler = ToolProvider.getSystemJavaCompiler();

        // Create a dummy dependency source file
        File dummyDir = tempFolder.newFolder("com", "example");
        File dummySource = new File(dummyDir, "DummyDependency.java");
        try (FileOutputStream out = new FileOutputStream(dummySource)) {
            out.write("package com.example; public class DummyDependency {}".getBytes());
        }

        // Compile it
        int res =
                compiler.run(
                        null,
                        null,
                        null,
                        "-d",
                        tempFolder.getRoot().getAbsolutePath(),
                        dummySource.getAbsolutePath());
        assertEquals("Failed to compile dummy dependency", 0, res);

        File classFile = new File(tempFolder.getRoot(), "com/example/DummyDependency.class");
        assertTrue(classFile.exists());

        // Package it into a JAR (the plugin only checks jar: URIs)
        dummyJar = tempFolder.newFile("dummy.jar");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(dummyJar))) {
            zos.putNextEntry(new ZipEntry("com/example/DummyDependency.class"));
            byte[] bytes = Files.readAllBytes(classFile.toPath());
            zos.write(bytes, 0, bytes.length);
            zos.closeEntry();
        }
    }

    private DiagnosticCollector<JavaFileObject> compileWithPluginWithSource(
            String pluginArgs, String sourceContent) throws Exception {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, null, null);

        File sourceFile = new File(tempFolder.getRoot(), "TestClass.java");
        try (FileOutputStream out = new FileOutputStream(sourceFile)) {
            out.write(sourceContent.getBytes());
        }

        Iterable<? extends JavaFileObject> compilationUnits =
                fileManager.getJavaFileObjectsFromFiles(Arrays.asList(sourceFile));

        String pluginPath =
                new File(
                                JavaStrictDepsPlugin.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI())
                        .getAbsolutePath();

        CompilationTask task =
                compiler.getTask(
                        null,
                        fileManager,
                        diagnostics,
                        Arrays.asList(
                                "-processorpath",
                                pluginPath,
                                "-cp",
                                dummyJar.getAbsolutePath(),
                                "-Xplugin:JavaStrictDeps " + pluginArgs),
                        null,
                        compilationUnits);

        task.call();
        return diagnostics;
    }

    @Test
    public void testThrowsExceptionIfRspMissing() throws Exception {
        File missingRsp = new File(tempFolder.getRoot(), "does_not_exist.rsp");
        try {
            compileWithPluginWithSource("rsp=" + missingRsp.getAbsolutePath(), DEFAULT_SOURCE);
            fail("Compilation should have thrown an exception for missing rsp file");
        } catch (IllegalArgumentException e) {
            assertTrue(
                    "Message check failed: " + e.getMessage(),
                    e.getMessage().contains("Missing .rsp file"));
        }
    }

    @Test
    public void testStrictDepsViolationError() throws Exception {
        File emptyRsp = tempFolder.newFile("empty.rsp");
        DiagnosticCollector<JavaFileObject> diagnostics =
                compileWithPluginWithSource("rsp=" + emptyRsp.getAbsolutePath(), DEFAULT_SOURCE);

        boolean foundViolation =
                diagnostics.getDiagnostics().stream()
                        .anyMatch(
                                d ->
                                        d.getKind() == Diagnostic.Kind.ERROR
                                                && d.getMessage(null)
                                                        .contains("Strict Dependencies Violation")
                                                && d.getMessage(null).contains("DummyDependency"));

        assertTrue(
                "Expected Strict Dependencies ERROR violation for DummyDependency", foundViolation);
    }

    @Test
    public void testStrictDepsViolationWarningMode() throws Exception {
        File emptyRsp = tempFolder.newFile("empty2.rsp");
        DiagnosticCollector<JavaFileObject> diagnostics =
                compileWithPluginWithSource(
                        "rsp=" + emptyRsp.getAbsolutePath() + " level=warn", DEFAULT_SOURCE);

        boolean foundWarning =
                diagnostics.getDiagnostics().stream()
                        .anyMatch(
                                d ->
                                        d.getKind() == Diagnostic.Kind.WARNING
                                                && d.getMessage(null)
                                                        .contains("Strict Dependencies Violation")
                                                && d.getMessage(null).contains("DummyDependency"));

        assertTrue(
                "Expected Strict Dependencies WARNING violation for DummyDependency", foundWarning);
    }

    @Test
    public void testStrictDepsAllowed() throws Exception {
        File populatedRsp = tempFolder.newFile("populated.rsp");
        Files.write(populatedRsp.toPath(), dummyJar.getAbsolutePath().getBytes());

        // Module legitimately declares dummy.jar in its deps
        DiagnosticCollector<JavaFileObject> diagnostics =
                compileWithPluginWithSource(
                        "rsp=" + populatedRsp.getAbsolutePath(), DEFAULT_SOURCE);

        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            if (diag.getMessage(null).contains("Strict Dependencies Violation")) {
                fail("Found unexpected violation: " + diag.getMessage(null));
            }
        }
    }

    /**
     * Verifies that the plugin ignores strict dependency violations for JARs explicitly listed in
     * the exemptions file (passed via the third argument).
     */
    @Test
    public void testStrictDepsExemptJars() throws Exception {
        File emptyRsp = tempFolder.newFile("empty_exempt.rsp");
        File exemptJarsFile = tempFolder.newFile("exempt_jars.txt");
        Files.write(exemptJarsFile.toPath(), dummyJar.getAbsolutePath().getBytes());

        DiagnosticCollector<JavaFileObject> diagnostics =
                compileWithPluginWithSource(
                        "rsp="
                                + emptyRsp.getAbsolutePath()
                                + " exemptions="
                                + exemptJarsFile.getAbsolutePath(),
                        DEFAULT_SOURCE);

        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            if (diag.getMessage(null).contains("Strict Dependencies Violation")) {
                fail("Found unexpected violation for exempt JAR: " + diag.getMessage(null));
            }
        }
    }

    /**
     * Verifies that the plugin ignores strict dependency violations for classes generated by
     * annotation processors when the processor name is listed in the exemptions file.
     */
    @Test
    public void testStrictDepsExemptProcessors() throws Exception {
        File emptyRsp = tempFolder.newFile("empty_exempt_proc.rsp");
        File exemptProcessorsFile = tempFolder.newFile("exempt_processors.txt");
        Files.write(exemptProcessorsFile.toPath(), "SomeProcessor".getBytes());

        // Create a source file with @Generated("SomeProcessor")
        String sourceContent =
                "import javax.annotation.processing.Generated;\n"
                        + "import com.example.DummyDependency;\n"
                        + "@Generated(value = \"SomeProcessor\")\n"
                        + "public class TestClass { \n"
                        + "    DummyDependency d;\n"
                        + "}\n";

        DiagnosticCollector<JavaFileObject> diagnostics =
                compileWithPluginWithSource(
                        "rsp="
                                + emptyRsp.getAbsolutePath()
                                + " exemptions="
                                + exemptProcessorsFile.getAbsolutePath(),
                        sourceContent);

        for (Diagnostic<? extends JavaFileObject> diag : diagnostics.getDiagnostics()) {
            if (diag.getMessage(null).contains("Strict Dependencies Violation")) {
                fail("Found unexpected violation for exempt processor: " + diag.getMessage(null));
            }
        }
    }
}
