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

import com.google.auto.service.AutoService;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.PackageTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.tree.JCTree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.SimpleAnnotationValueVisitor8;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

/**
 * Implements strict dependency enforcement inspired by Bazel's architecture. Checks classes used in
 * the source graph against a list of direct dependencies (.rsp). Emits errors or warnings if a
 * class is drawn from a transitive dependency without being explicitly declared in the direct
 * dependencies.
 *
 * <p>Tailored for the Android Build System (Soong) using standard javac `-Xplugin` hooks.
 */
@AutoService(Plugin.class)
public final class JavaStrictDepsPlugin implements Plugin {
    public static final String NAME = "JavaStrictDeps";
    private static final String JAR_URI_PREFIX = "jar:file:";

    /**
     * Required by the {@link Plugin} interface. The string returned here dictates the exact
     * command-line argument required to trigger this plugin in `javac` (e.g.,
     * `-Xplugin:JavaStrictDeps`).
     */
    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init(JavacTask task, String... args) {
        Map<String, String> parsedArgs = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq != -1) {
                parsedArgs.put(arg.substring(0, eq), arg.substring(eq + 1));
            }
        }

        String rspPath = parsedArgs.get("rsp");
        if (rspPath == null) {
            throw new IllegalArgumentException("[JavaStrictDeps] Missing required argument: rsp");
        }

        Path rspFile = Paths.get(rspPath);
        if (!Files.exists(rspFile)) {
            throw new IllegalArgumentException("[JavaStrictDeps] Missing .rsp file: " + rspFile);
        }

        Set<String> allowedJars = new HashSet<>();
        try {
            List<String> lines = Files.readAllLines(rspFile);
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    allowedJars.add(Paths.get(line.trim()).toAbsolutePath().toString());
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read: " + rspFile, e);
        }

        boolean warnMode = "warn".equals(parsedArgs.get("level"));

        // The exemptions file contains absolute paths (for JARs) or class names (for processors).
        //
        // We exempt three categories to reduce build noise and prevent false positives:
        //
        // 1. Manual Whitelist (e.g., androidx.annotation.jar):
        //    Silences noise for trusted platform-level libraries that Soong provides automatically.
        // 2. Dynamic Resources (e.g., R.jar):
        //    Saves build noise from auto-generated resource bindings. R.jar is a build artifact,
        //    not a standard dependency.
        // 3. Plugin Class Names (e.g., AutoValue, Dagger):
        //    Skips validation on files with @Generated annotations from these processors to avoid
        //    false positives triggered by generated boilerplate code.
        Set<String> exemptions = new HashSet<>();
        String exemptionsPath = parsedArgs.get("exemptions");
        if (exemptionsPath != null && !exemptionsPath.isEmpty()) {
            Path exemptionsFile = Paths.get(exemptionsPath);
            if (Files.exists(exemptionsFile)) {
                try {
                    List<String> lines = Files.readAllLines(exemptionsFile);
                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty()) {
                            if (trimmed.endsWith(".jar")) {
                                exemptions.add(Paths.get(trimmed).toAbsolutePath().toString());
                            } else {
                                exemptions.add(trimmed);
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to read: " + exemptionsFile, e);
                }
            }
        }

        final Trees trees = Trees.instance(task);
        final boolean finalWarnMode = warnMode;

        ValidationContext context =
                new ValidationContext(trees, allowedJars, exemptions, finalWarnMode);

        task.addTaskListener(new StrictDepsTaskListener(context));
    }

    private static class ValidationContext {
        final Trees trees;
        final Set<String> allowedJars;
        final Set<String> exemptions;
        final boolean warnMode;
        final Set<ClassSymbol> seenClasses = new HashSet<>();
        final Set<String> violatingJars = new HashSet<>();

        ValidationContext(
                Trees trees, Set<String> allowedJars, Set<String> exemptions, boolean warnMode) {
            this.trees = trees;
            this.allowedJars = allowedJars;
            this.exemptions = exemptions;
            this.warnMode = warnMode;
        }
    }

    /**
     * Scanner for transitive dependency violations. Aligned with Bazel's CheckingTreeScanner.
     *
     * <p>We override all the same visit methods as Bazel except for {@code visitCompilationUnit}.
     * In Bazel, the plugin hooks into {@code postAttribute} (triggered per class environment) and
     * overrides {@code visitCompilationUnit} to skip {@code TypeDecls} to avoid duplicate scanning.
     * Since this AOSP plugin hooks into the {@code ANALYZE} event (which triggers once per file),
     * we want the default behavior (which scans everything including {@code TypeDecls}) to analyze
     * the entire file.
     */
    private static class StrictDepsScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree compilationUnit;
        private final ValidationContext context;

        StrictDepsScanner(CompilationUnitTree compilationUnit, ValidationContext context) {
            this.compilationUnit = compilationUnit;
            this.context = context;
        }

        /** Visits an identifier in the AST. We only care about type symbols. */
        @Override
        public Void visitIdentifier(IdentifierTree tree, Void unused) {
            checkTypeLiteral(((JCTree.JCIdent) tree).sym, tree);
            return null;
        }

        /**
         * Visits a field selection in the AST. We care because in some cases types may appear fully
         * qualified and only inside a field selection (e.g., "com.foo.Bar.X", we want to catch the
         * reference to Bar).
         */
        @Override
        public Void visitMemberSelect(MemberSelectTree tree, Void unused) {
            scan(tree.getExpression(), null);
            checkTypeLiteral(((JCTree.JCFieldAccess) tree).sym, tree);
            return null;
        }

        /**
         * Visits a method declaration. We scan its parameters, return type, and body to catch
         * usages (e.g., `public Bar doSomething(Foo f) { ... }`). For generated constructors, we
         * only scan the body to avoid synthetic metadata false positives.
         */
        @Override
        public Void visitMethod(MethodTree method, Void unused) {
            if ((((JCTree.JCMethodDecl) method).mods.flags & Flags.GENERATEDCONSTR) != 0) {
                scan(method.getBody(), null);
            } else {
                super.visitMethod(method, null);
            }
            return null;
        }

        /**
         * Visits a package declaration. We check package-level annotations for unauthorized
         * dependencies (e.g., `@com.foo.MyAnnotation package com.android;`).
         */
        @Override
        public Void visitPackage(PackageTree tree, Void unused) {
            scan(tree.getAnnotations(), null);
            checkTypeLiteral(((JCTree.JCPackageDecl) tree).packge.package_info, tree);
            return null;
        }

        /**
         * Visits a variable declaration. Scans the type (if explicit), nameExpression, and
         * initializer to catch usages (e.g., `com.foo.Bar b = ...`).
         */
        @Override
        public Void visitVariable(VariableTree variable, Void unused) {
            scan(variable.getModifiers(), null);
            if (!hasImplicitType(variable)) {
                scan(variable.getType(), null);
            }
            scan(variable.getNameExpression(), null);
            scan(variable.getInitializer(), null);
            return null;
        }

        /**
         * Checks if a variable has an implicit type to see if we should skip scanning its type
         * (e.g., `var x = ...` or omitted synthetic variables).
         */
        private boolean hasImplicitType(VariableTree tree) {
            JCTree.JCVariableDecl varDecl = (JCTree.JCVariableDecl) tree;
            if (varDecl.declaredUsingVar()) {
                return true;
            }
            JCTree vartype = varDecl.vartype;
            if (vartype != null && vartype.pos == com.sun.tools.javac.util.Position.NOPOS) {
                return true;
            }
            return false;
        }

        /**
         * Visits a lambda expression. If parameters have explicit types (e.g., `(com.foo.Bar b) ->
         * ...`), we check them. If inferred, we skip them to avoid checking synthetic types.
         */
        @Override
        public Void visitLambdaExpression(LambdaExpressionTree tree, Void unused) {
            if (((JCTree.JCLambda) tree).paramKind != JCTree.JCLambda.ParameterKind.IMPLICIT) {
                scan(tree.getParameters(), null);
            }
            scan(tree.getBody(), null);
            return null;
        }

        /**
         * Validates that a symbol's type is an authorized dependency and records resolved usages
         * (e.g., if we see `com.unauthorized.Bar`, we check if it is in the allowed list).
         */
        private void checkTypeLiteral(Symbol sym, Tree reportNode) {
            if (!(sym instanceof ClassSymbol)) {
                return;
            }
            ClassSymbol classSym = (ClassSymbol) sym;

            if (!context.seenClasses.add(classSym)) {
                return;
            }

            String jarPath = getNonPlatformJarPath(classSym);
            if (jarPath != null) {
                collectExplicitDependency(jarPath, classSym, reportNode);
            }
        }

        private String getNonPlatformJarPath(ClassSymbol classSym) {
            JavaFileObject classfile = classSym.classfile;
            if (classfile == null) {
                return null;
            }

            String uri = classfile.toUri().toString();
            String jarPath = "";

            if (uri.startsWith(JAR_URI_PREFIX)) {
                int bangIndex = uri.indexOf("!");
                if (bangIndex != -1) {
                    jarPath = uri.substring(JAR_URI_PREFIX.length(), bangIndex);
                }
            }
            if (jarPath.isEmpty()) {
                return null;
            }

            String absoluteJarPath = Paths.get(jarPath).toAbsolutePath().toString();
            if (context.exemptions.contains(absoluteJarPath)) {
                return null;
            }

            return absoluteJarPath;
        }

        private void collectExplicitDependency(
                String absoluteJarPath, ClassSymbol classSym, Tree reportNode) {
            if (!context.allowedJars.contains(absoluteJarPath)) {
                if (context.violatingJars.add(absoluteJarPath)) {
                    String msg =
                            String.format(
                                    "\n"
                                            + "** Strict Dependencies Violation **\n"
                                            + "Uses class '%s' from %s, which is not a direct"
                                            + " dependency.\n",
                                    classSym.getQualifiedName(), absoluteJarPath);
                    context.trees.printMessage(
                            context.warnMode ? Diagnostic.Kind.WARNING : Diagnostic.Kind.ERROR,
                            msg,
                            reportNode,
                            compilationUnit);
                }
            }
        }
    }

    private static class StrictDepsTaskListener implements TaskListener {
        private final ValidationContext context;

        StrictDepsTaskListener(ValidationContext context) {
            this.context = context;
        }

        @Override
        public void started(TaskEvent e) {}

        @Override
        public void finished(TaskEvent e) {
            if (e.getKind() != TaskEvent.Kind.ANALYZE) {
                return;
            }

            CompilationUnitTree compilationUnit = e.getCompilationUnit();

            if (isAnnotationProcessorExempt(context.trees, compilationUnit, context.exemptions)) {
                return;
            }

            StrictDepsScanner scanner = new StrictDepsScanner(compilationUnit, context);
            scanner.scan(compilationUnit, null);
        }
    }

    // Check if the file should be skipped because it was generated by an exempt processor.
    //
    // To qualify for exemption, the file MUST contain exactly one top-level class. This prevents
    // a loophole where manual code is mixed into a generated file to bypass strict dependency
    // checking.
    // If it has exactly one class, we read its @Generated elements to see if they match an exempt
    // processor plugin.
    private static boolean isAnnotationProcessorExempt(
            Trees trees, CompilationUnitTree unit, Set<String> exemptions) {
        if (unit.getTypeDecls().size() != 1) {
            return false;
        }
        Tree firstDecl = unit.getTypeDecls().get(0);
        com.sun.source.util.TreePath path = com.sun.source.util.TreePath.getPath(unit, firstDecl);
        Element element = trees.getElement(path);
        if (element == null) {
            return false;
        }
        for (String value : getGeneratedBy(element)) {
            if (exemptions.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> getGeneratedBy(Element element) {
        Set<String> suppressions = new HashSet<>();
        element.getAnnotationMirrors().stream()
                .filter(
                        a -> {
                            TypeElement type = (TypeElement) a.getAnnotationType().asElement();
                            Name name = type.getQualifiedName();
                            return name.contentEquals("javax.annotation.Generated")
                                    || name.contentEquals("javax.annotation.processing.Generated");
                        })
                .flatMap(
                        a ->
                                a.getElementValues().entrySet().stream()
                                        .filter(
                                                e ->
                                                        e.getKey()
                                                                .getSimpleName()
                                                                .contentEquals("value"))
                                        .map(e -> e.getValue()))
                .forEachOrdered(
                        a ->
                                a.accept(
                                        new SimpleAnnotationValueVisitor8<Void, Void>() {
                                            @Override
                                            public Void visitString(String s, Void aVoid) {
                                                suppressions.add(s);
                                                return super.visitString(s, aVoid);
                                            }

                                            @Override
                                            public Void visitArray(
                                                    List<? extends AnnotationValue> vals,
                                                    Void aVoid) {
                                                vals.forEach(v -> v.accept(this, null));
                                                return super.visitArray(vals, aVoid);
                                            }
                                        },
                                        null));
        return suppressions;
    }
}
