import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application") version "9.1.0" apply false
    kotlin("jvm") version "2.4.10" apply false
    id("org.jmailen.kotlinter") version "5.6.0" apply false
}

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

abstract class KotlinQualityCheck : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @TaskAction
    fun verifySources() {
        val violations = mutableListOf<String>()

        sourceFiles.files.sortedBy { it.invariantSeparatorsPath }.forEach { file ->
            val path = file.invariantSeparatorsPath
            val text = file.readText()

            if (text.isNotEmpty() && !text.endsWith("\n")) {
                violations += "$path: 文件末尾缺少换行"
            }

            text.lineSequence().forEachIndexed { index, line ->
                if ('\t' in line) {
                    violations += "$path:${index + 1}: 包含 Tab"
                }
                if (line.endsWith(' ') || line.endsWith('\t')) {
                    violations += "$path:${index + 1}: 包含行尾空白"
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Kotlin 格式检查失败：")
                    violations.forEach { appendLine("- $it") }
                },
            )
        }
    }
}

val kotlinQualityCheck by tasks.registering(KotlinQualityCheck::class) {
    group = "verification"
    description = "检查 Kotlin 源码与构建脚本的稳定基础格式"
    sourceFiles.from(
        fileTree(rootDir) {
            include("**/*.kt", "**/*.kts")
            exclude("**/build/**", "**/.gradle/**")
        },
    )
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("kotlinQualityCheck"))
    }
}
