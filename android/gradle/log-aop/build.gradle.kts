import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "1.9.24"
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // 只在编译期用 AGP 的 Instrumentation API；运行时由宿主构建提供
    compileOnly("com.android.tools.build:gradle:8.4.2")
    implementation("org.ow2.asm:asm:9.7")
    implementation("org.ow2.asm:asm-commons:9.7")
}

gradlePlugin {
    plugins {
        create("logAop") {
            id = "log-aop"
            implementationClass = "com.dshpet.gradle.LogAopPlugin"
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
