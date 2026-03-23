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
import com.sun.source.util.JavacTask;
import com.sun.source.util.Plugin;

/**
 * This plugin implements a strict dependencies enforcement strategy inspired by the Bazel build
 * system's architecture.
 *
 * <p>Reference:
 * https://github.com/bazelbuild/bazel/blob/master/src/java_tools/buildjar/java/com/google/devtools/build/buildjar/javac/plugins/dependency/StrictJavaDepsPlugin.java
 *
 * <p>**Architecture Alignment Note**: While the core analysis logic aligns with the strict
 * dependency checks in Bazel, the integration mechanics differ significantly. Bazel embeds this
 * analysis into its internal `buildjar` pipeline (e.g., `BlazeJavaCompilerPlugin`).
 *
 * <p>In contrast, this implementation is tailored for the Android Build System (Soong), utilizing
 * the standard `com.sun.source.util.Plugin` interface to interact with `javac` via `-Xplugin`
 * injection on top of AOSP's generated classpath lists and `.rsp` files.
 */
@AutoService(Plugin.class)
public final class StrictJavaDepsPlugin implements Plugin {
    public static final String NAME = "JavaStrictDeps";

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
        if (args.length == 0) {
            throw new IllegalArgumentException(
                    "[JavaStrictDeps] Missing required configuration file argument.");
        }
    }
}
