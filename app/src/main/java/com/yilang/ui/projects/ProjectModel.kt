package com.yilang.ui.projects

import com.yilang.model.YiFile
import java.io.File

/** 项目类型：蚁语言（中文转译 C++）或纯 C++ */
enum class ProjectType(val label: String, val ext: String) {
    YILANG("蚁语言", "yl"),
    CPP("C++", "cpp");

    companion object {
        /** 按主文件探测类型：main.cpp → C++，其余视为蚁语言（兼容旧项目） */
        fun detect(dir: File): ProjectType =
            if (File(dir, "main.cpp").exists()) CPP else YILANG
    }
}

/** 构建方式：编译 C++ 的工具链（不含 JNI，无 Java/Kt 宿主需求） */
enum class BuildSystem(val label: String) {
    NDK_BUILD("ndk-build"),
    CLANG("Clang"),
    CMAKE("CMake");

    companion object {
        /** 构建配置存放在项目目录下的 build.yi */
        const val CONFIG_FILE = "build.yi"

        fun detect(dir: File): BuildSystem {
            val f = File(dir, CONFIG_FILE)
            val s = if (f.exists()) f.readText(Charsets.UTF_8).trim() else ""
            return entries.firstOrNull {
                it.name.equals(s, ignoreCase = true) || it.label.equals(s, ignoreCase = true)
            } ?: CLANG
        }
    }
}

/** 项目模型：对应 <filesDir>/projects/<项目名>/ 目录 */
data class Project(
    val name: String,
    val dir: File,
    val type: ProjectType = ProjectType.detect(dir),
    val buildSystem: BuildSystem = BuildSystem.detect(dir),
) {
    /** 项目内全部源码文件（.yl + .cpp） */
    fun sourceFiles(): List<YiFile> =
        dir.listFiles { f -> f.isFile && (f.extension == "yl" || f.extension == "cpp") }
            ?.sortedBy { it.name }
            ?.map { YiFile(it.name, it) }
            ?: emptyList()

    companion object {
        const val TEMPLATE = """// 蚁语言示例：用中文写，让 C++ 干活
函数 整数 相加(整数 a, 整数 b) {
    返回 a + b
}

设 整数 总和 = 相加(3, 4)
输出("3 + 4 = ", 总和)

对于 (设 整数 i = 1; i <= 3; i++) {
    输出("第", i, "次循环")
}

设 布尔 开关 = 真
如果 (开关 并且 总和 > 5) {
    输出("开关已打开，16 的平方根是", 平方根(16))
}
"""

        const val CPP_TEMPLATE = """#include <cstdio>

int main() {
    printf("你好，蚁语言\n");
    return 0;
}
"""

        /** CMake 项目：默认 CMakeLists.txt（蚁语言项目转译产物 main.cpp 后即可由此构建） */
        private fun cmakeLists(name: String) = """cmake_minimum_required(VERSION 3.22.1)
project($name CXX)

add_executable(main main.cpp)
"""

        /** ndk-build 项目：默认 Android.mk（编译为可执行文件，非 JNI 动态库） */
        const val ANDROID_MK = """LOCAL_PATH := ${'$'}(call my-dir)

include ${'$'}(CLEAR_VARS)

LOCAL_MODULE := main
LOCAL_SRC_FILES := main.cpp

include ${'$'}(BUILD_EXECUTABLE)
"""

        /** ndk-build 项目：默认 Application.mk（arm64 + 静态 STL，与 minSdk 26 对齐） */
        const val APPLICATION_MK = """APP_ABI := arm64-v8a
APP_STL := c++_static
APP_PLATFORM := android-26
"""

        fun projectsRoot(ctx: android.content.Context): File =
            File(ctx.filesDir, "projects").apply { mkdirs() }

        /** 扫描目录得到项目列表（按名称排序） */
        fun loadAll(ctx: android.content.Context): List<Project> {
            val root = projectsRoot(ctx)
            return root.listFiles { f -> f.isDirectory }
                ?.map { Project(it.name, it) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }

        /** 新建项目：目录 + 按类型写入模板主文件 + 构建配置 */
        fun create(ctx: android.content.Context, name: String, type: ProjectType, build: BuildSystem): Project {
            val dir = File(projectsRoot(ctx), name)
            dir.mkdirs()
            val main = File(dir, "main.${type.ext}")
            if (!main.exists()) {
                main.writeText(if (type == ProjectType.CPP) CPP_TEMPLATE else TEMPLATE, Charsets.UTF_8)
            }
            File(dir, BuildSystem.CONFIG_FILE).writeText(build.name, Charsets.UTF_8)
            // 按构建方式生成默认配置文件（已存在则不覆盖，保留用户修改）
            when (build) {
                BuildSystem.CMAKE -> {
                    val f = File(dir, "CMakeLists.txt")
                    if (!f.exists()) f.writeText(cmakeLists(name), Charsets.UTF_8)
                }
                BuildSystem.NDK_BUILD -> {
                    val mk = File(dir, "Android.mk")
                    if (!mk.exists()) mk.writeText(ANDROID_MK, Charsets.UTF_8)
                    val appMk = File(dir, "Application.mk")
                    if (!appMk.exists()) appMk.writeText(APPLICATION_MK, Charsets.UTF_8)
                }
                BuildSystem.CLANG -> Unit // 直接命令行编译，无需配置文件
            }
            return Project(name, dir, type, build)
        }

        fun delete(project: Project) {
            project.dir.deleteRecursively()
        }

        fun rename(project: Project, newName: String): Project? {
            val target = File(project.dir.parentFile, newName)
            if (target.exists()) return null
            return if (project.dir.renameTo(target)) {
                Project(newName, target, project.type, project.buildSystem)
            } else null
        }
    }
}
