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

package com.android.strictdeps

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Assert.assertEquals
import org.junit.Test

class KotlinStrictDepsPluginTest {

  @Test
  fun testPluginId() {
    val processor = KotlinStrictDepsCommandLineProcessor()
    assertEquals(KOTLIN_STRICT_DEPS_PLUGIN_ID, processor.pluginId)
  }

  @Test
  fun testProcessOption() {
    val processor = KotlinStrictDepsCommandLineProcessor()
    val configuration = CompilerConfiguration()

    // Grab the officially registered config option from the processor
    val opt = processor.pluginOptions.first { it.optionName == RSP_OPTION_NAME }

    // Emulate the Kotlinc engine executing our processor with the rsp path flag
    processor.processOption(opt, "mock_dependencies.rsp", configuration)

    // Verify the processor correctly extracted and stored it in the internal map using our secure
    // Key
    assertEquals("mock_dependencies.rsp", configuration.get(RSP_FILE_KEY))
  }
}
