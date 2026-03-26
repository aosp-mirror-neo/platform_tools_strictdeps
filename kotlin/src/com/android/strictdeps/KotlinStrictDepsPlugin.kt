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

import com.google.auto.service.AutoService
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

/**
 * KotlinStrictDeps Plugin
 *
 * This compiler plugin enforces strict dependencies on Kotlin compilations. It ensures that any
 * types referenced in the current compilation are provided directly by the module's dependencies,
 * preventing implicit/transitive usage.
 *
 * Reference:
 * https://github.com/bazelbuild/rules_kotlin/tree/master/src/main/kotlin/io/bazel/kotlin/plugin/jdeps
 *
 * **Architecture Alignment Note**: While the core analysis logic aligns with the strict dependency
 * checks in Bazel's `rules_kotlin`, the integration mechanics differ significantly.
 *
 * In contrast to Bazel which might embed its FIR analysis into its worker pipeline, this
 * implementation is tailored for the Android Build System (Soong), utilizing the standard
 * `CompilerPluginRegistrar` and `CommandLineProcessor` interfaces to interact seamlessly with K2
 * `kotlinc` via standard `-Xplugin` injection on top of AOSP's generated `.rsp` files.
 */

/**
 * Unique identifier for this plugin. This is required by the CommandLineProcessor interface so that
 * `kotlinc` knows to route plugin configuration arguments (e.g. `-P
 * plugin:com.android.strictdeps:rsp=...`) to our processor.
 */
const val KOTLIN_STRICT_DEPS_PLUGIN_ID = "com.android.strictdeps"
const val RSP_OPTION_NAME = "rsp"
const val LEVEL_OPTION_NAME = "level"

/**
 * Type-safe keys used to store/retrieve the path to the strict dependencies configuration file
 * (typically an AOSP generated `.rsp` file containing direct dependency jars) and the enforcement
 * level within the Kotlin compiler's shared configuration map.
 */
val RSP_FILE_KEY = CompilerConfigurationKey.create<String>(RSP_OPTION_NAME)
val LEVEL_KEY = CompilerConfigurationKey.create<String>(LEVEL_OPTION_NAME)

@OptIn(ExperimentalCompilerApi::class)
@AutoService(CommandLineProcessor::class)
class KotlinStrictDepsCommandLineProcessor : CommandLineProcessor {
  override val pluginId: String = KOTLIN_STRICT_DEPS_PLUGIN_ID
  override val pluginOptions: Collection<AbstractCliOption> =
      listOf(
          CliOption(
              RSP_OPTION_NAME,
              "<path>",
              "Missing required configuration file argument.",
              required = true,
          ),
          CliOption(
              LEVEL_OPTION_NAME,
              "<level>",
              "Enforcement level (warn, error).",
              required = false,
          ),
      )

  override fun processOption(
      option: AbstractCliOption,
      value: String,
      configuration: CompilerConfiguration,
  ) {
    when (option.optionName) {
      RSP_OPTION_NAME -> configuration.put(RSP_FILE_KEY, value)
      LEVEL_OPTION_NAME -> configuration.put(LEVEL_KEY, value)
      else ->
          throw IllegalArgumentException(
              "[KotlinStrictDeps] Unexpected config option ${option.optionName}"
          )
    }
  }
}

/**
 * The primary execution entry point for the Strict Deps compiler plugin.
 *
 * `CompilerPluginRegistrar` (which natively replaces the legacy K1 `ComponentRegistrar`) tells the
 * Kotlin compiler engine exactly which K2/FIR extension points our strict dependency analysis logic
 * needs to hook into!
 */
@OptIn(ExperimentalCompilerApi::class)
@AutoService(CompilerPluginRegistrar::class)
class KotlinStrictDepsRegistrar : CompilerPluginRegistrar() {
  override val supportsK2: Boolean = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val rspFile =
        configuration.get(RSP_FILE_KEY)
            ?: throw IllegalArgumentException(
                "[KotlinStrictDeps] Missing required configuration file argument."
            )
    val level = configuration.get(LEVEL_KEY) ?: "error"
  }
}
