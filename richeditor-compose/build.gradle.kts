import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * richeditor-compose 模块构建配置
 *
 * 改造说明：
 * 原为 KMP 多平台模块（JetBrains Compose Multiplatform），现已改造为
 * 仅保留 Android target 的 KMP 模块，使用 AndroidX Compose 依赖。
 *
 * AGP 9.0+ 迁移（2026-07-10）：
 * - com.android.library → com.android.kotlin.multiplatform.library（KMP 专用插件）
 * - 顶层 android {} 块迁移到 kotlin { android {} } 内
 * - androidTarget {} 块被 kotlin { android {} } 替代
 *
 * Kotlin 2.3 兼容性修复：
 * - KMP 的 KotlinDependencyHandler.platform() 已废弃（KT-58759），不能在 sourceSets 中使用
 * - BOM 引入移到顶层 dependencies {} 块，使用 commonMainImplementation 配置 +
 *   Gradle 原生 DependencyHandler.platform() 函数（不受 KMP 废弃影响）
 *
 * 其他变更：
 * - 移除 composeMultiplatform 插件（org.jetbrains.compose）
 * - 移除 bcv（Binary Compatibility Validator）
 * - 移除 module.publication 自定义 convention plugin
 * - 移除 jvm/js/wasmJs/ios 等 target
 * - commonMain 依赖从 org.jetbrains.compose.* 改为 androidx.compose.*
 * - SDK 版本对齐根项目（compileSdk=35, minSdk=26）
 */

plugins {
    /** 以下插件用 id() 不带版本应用（已在根项目声明 apply false，
     *  且 AGP 9.2.1 已将它们加入 classpath） */
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()

    /** AGP 9.0+ KMP 专用 DSL：android 配置移入 kotlin 块内
     *  新插件简化了 DSL：直接用 namespace/compileSdk/minSdk，
     *  不再需要 defaultConfig {} 和 compileOptions {} 块 */
    android {
        namespace = "com.mohamedrejeb.richeditor.compose"
        compileSdk = 35
        minSdk = 26

        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // 启用 Android host tests，避免 commonTest 源目录存在但未启用的警告
        withHostTest {}
    }

    sourceSets.commonMain.dependencies {
        // AndroidX Compose（版本由顶层 dependencies 中的 BOM 统一管理）
        implementation("androidx.compose.runtime:runtime")
        implementation("androidx.compose.foundation:foundation")
        implementation("androidx.compose.material:material")
        implementation("androidx.compose.material3:material3")

        // HTML 解析库
        implementation("com.mohamedrejeb.ksoup:ksoup-html:0.6.0")
        implementation("com.mohamedrejeb.ksoup:ksoup-entities:0.6.0")

        // Markdown 解析库
        implementation("org.jetbrains:markdown:0.7.7")
    }

    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}

/** Compose BOM 引入方案（AGP 9.0+ KMP 模块）：
 *  - 问题1：KMP 的 KotlinDependencyHandler.platform() 在 Kotlin 2.3 废弃（KT-58759），
 *    不能在 sourceSets.commonMain.dependencies {} 中使用 platform()
 *  - 问题2：新插件 com.android.kotlin.multiplatform.library 不创建标准 implementation 配置，
 *    顶层 dependencies {} 中 implementation 未解析
 *  - 解决：使用 KMP 为 commonMain 源集注册的 commonMainImplementation 配置，
 *    配合 Gradle 原生 DependencyHandler.platform()（非 KMP 废弃的那个）
 *  - 语法：用字符串调用 "commonMainImplementation"(...) 因为该配置由 KMP 插件动态注册，
 *    不在脚本编译期作为类型化函数存在 */
dependencies {
    "commonMainImplementation"(platform("androidx.compose:compose-bom:2026.04.01"))
}
