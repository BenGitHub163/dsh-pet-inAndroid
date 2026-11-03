import java.util.Properties
import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // 日志 AOP：编译期给 @TraceLog 方法插 enter/exit（实现见 buildSrc/LogAopPlugin.kt）
    id("log-aop")
}

android {
    namespace = "com.dshpet.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dshpet.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 437
        versionName = "4.3.7"

        // ---- 内置 Key（**不在源码里硬编码**）----
        // 自己用：把 key 写在 `android/keys.properties`（这个文件不进版本库、不进代码）：
        //     deepseek=sk-xxxxxxxx
        //     doubao=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
        // 给别人：`.\gradlew.bat :app:assembleRelease "-Pdsh.noKey=true"` → 两个 key 都注入空串，
        //        朋友必须在设置页填自己的 key（保存在 DataStore 里）。
        // 代码侧统一通过 `data/KeyFactory` 取，不直接碰 BuildConfig。
        val noKey = project.hasProperty("dsh.noKey")
        val keysFile = rootProject.file("keys.properties")
        val props = Properties().apply {
            if (!noKey && keysFile.exists()) keysFile.inputStream().use { load(it) }
        }
        fun key(name: String, fallback: String = ""): String =
            if (noKey) "" else (props.getProperty(name) ?: fallback)
        // 构建戳：每次出包都不同，排障第一件事就是确认"手机上跑的是哪一版"
        buildConfigField("String", "BUILD_STAMP", "\"${SimpleDateFormat("MM-dd HH:mm").format(Date())}\"")
        buildConfigField("String", "DEFAULT_DEEPSEEK_KEY", "\"${key("deepseek")}\"")
        buildConfigField("String", "DEFAULT_DOUBAO_KEY", "\"${key("doubao")}\"")
        buildConfigField("String", "DEFAULT_DOUBAO_APPID", "\"${key("doubao_appid")}\"")
        buildConfigField("String", "DEFAULT_BREEZE_KEY", "\"${key("breeze")}\"")
        logger.lifecycle(
            "内置 Key：deepseek=" + (if (key("deepseek").isBlank()) "（空）" else "已配置") +
                " doubao=" + (if (key("doubao").isBlank()) "（空）" else "已配置") +
                " breeze=" + (if (key("breeze").isBlank()) "（空）" else "已配置") +
                " 来源=" + (if (keysFile.exists() && !noKey) "keys.properties" else "无"),
        )
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/dshpet-release.keystore")
            storePassword = "dshpet123"
            keyAlias = "dshpet"
            keyPassword = "dshpet123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.2")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 提示词用 YAML 组织（assets/prompts/prompts.yaml）
    implementation("org.yaml:snakeyaml:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- 单元测试（只进 test 类路径，不进 APK；对应 TESTPLAN §2）----
    // 工具解析/累加器写成纯类（不碰 Context / android.util.Log），才能在 JVM 上直接断言。
    testImplementation("junit:junit:4.13.2")
    // Android 自带的 org.json 在 JVM 单测里是抛 "Stub!" 的空壳，必须换真实实现
    testImplementation("org.json:json:20240303")
}

// ============================================================================
// checkApiHosts：域名/接口地址只允许出现在 api/ 目录
// ----------------------------------------------------------------------------
// 背景：key + 站点 的调用一度散落在 provider、UI、配置层（同一个 deepseek 地址写了 5 遍），
// 换代理/换模型就会漏改。这个任务把"合规边界"变成构建期约束：
//   · app/src/main/**/*.kt 里出现 http(s):// 且文件不在 api/ 下 → 失败；
//   · 存量债写在 app/api-hosts-allowlist.txt，迁移一个删一行；
//   · 清单里的文件如果已经不再含 URL，会提示把这行删掉（防止清单腐烂）。
// ============================================================================
val apiHostsAllowlist = rootProject.file("app/api-hosts-allowlist.txt")

val checkApiHosts by tasks.registering {
    group = "verification"
    description = "禁止在 api/ 以外硬编码接口域名（key + 站点 必须收敛到 ApiFactory 接口层）"
    val srcDir = file("src/main/java")
    val allowFile = apiHostsAllowlist
    inputs.dir(srcDir).withPropertyName("sources")
    if (allowFile.exists()) inputs.file(allowFile).withPropertyName("allowlist")
    doLast {
        val allow = if (allowFile.exists()) {
            allowFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toSet()
        } else {
            emptySet<String>()
        }
        val urlRegex = Regex("""https?://[^\s"'`)]+""")
        val violations = mutableListOf<String>()
        val stale = allow.toMutableSet()
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(srcDir).invariantSeparatorsPath
            if (rel.startsWith("com/dshpet/android/api/")) return@forEach
            val hits = f.readLines().withIndex()
                .filter { (_, line) -> urlRegex.containsMatchIn(line) }
                .mapNotNull { (i, line) ->
                    val u = urlRegex.find(line)?.value ?: return@mapNotNull null
                    // 文档/仓库链接不是接口调用（github.com 的 Releases 页、docs.* 文档）
                    if (u.startsWith("https://github.com/") || u.startsWith("https://docs.")) null
                    else "${i + 1}: $u"
                }
            if (hits.isEmpty()) return@forEach
            if (rel in allow) {
                stale.remove(rel)
                logger.lifecycle("  [已知债] $rel (${hits.size} 处)")
            } else {
                hits.forEach { violations += "  $rel:$it" }
            }
        }
        if (stale.isNotEmpty()) {
            logger.warn("api-hosts-allowlist.txt 里这些文件已不再含 URL，请删掉对应行：")
            stale.forEach { logger.warn("  - $it") }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("发现 api/ 之外的硬编码接口地址（${violations.size} 处）：")
                    violations.forEach { appendLine(it) }
                    appendLine("规则：域名/路径只写在 api/ 下的接口类里；业务层通过 ApiFactory 调用。")
                    appendLine("存量迁移中：可临时加进 app/api-hosts-allowlist.txt，但不要新增。")
                },
            )
        }
        // ---- 第二条规则（失败模式）：HTTP 调用只允许在 api/ 内 ----
        // 已切失败：TTS 三个 provider 走 TtsCodec + api/TtsApi，对话走 api/ChatStream。
        // `\b` 不能省：SpeechPlayer 里的 `AudioFocusRequest.Builder(...)` 是 AudioTrack 的焦点请求，
        // 不是 HTTP，去掉词边界就变成永久误报。
        val httpMarker = Regex("""\bRequest\.Builder\(|\.newCall\(|toRequestBody\(""")
        val httpOffenders = mutableListOf<String>()
        srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val rel = f.relativeTo(srcDir).invariantSeparatorsPath
            if (rel.startsWith("com/dshpet/android/api/")) return@forEach
            val n = f.readLines().count { httpMarker.containsMatchIn(it) }
            if (n > 0) httpOffenders += "$rel ($n 处)"
        }
        if (httpOffenders.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("发现 api/ 之外的 HTTP 调用（${httpOffenders.size} 个文件）：")
                    httpOffenders.forEach { appendLine("  - $it") }
                    appendLine("规则：请求组装与发送只写在 api/ 下。")
                    appendLine("新增服务商：写 api/XxxCodec.kt（只拼报文/解流），由 api/TtsApi 发；")
                    appendLine("对话类接口写进 api/ChatStream.kt，不要在业务层 newCall。")
                },
            )
        }
        logger.lifecycle("checkApiHosts: 通过（api/ 之外无硬编码域名、无 HTTP 调用）")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(checkApiHosts) }