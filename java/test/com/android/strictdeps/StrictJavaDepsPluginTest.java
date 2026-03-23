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

import static org.junit.Assert.*;

import org.junit.Test;

public class StrictJavaDepsPluginTest {
    @Test
    public void testPluginName() {
        StrictJavaDepsPlugin plugin = new StrictJavaDepsPlugin();
        assertEquals("JavaStrictDeps", plugin.getName());
    }

    @Test
    public void testPluginInit() {
        StrictJavaDepsPlugin plugin = new StrictJavaDepsPlugin();
        // plugin.init doesn't use the JavacTask for now, so null is safe for skeleton testing
        try {
            plugin.init(null, "direct_deps.rsp", "warn");
        } catch (Exception e) {
            fail("Plugin init should not throw Exception: " + e.getMessage());
        }
    }
}
