# 蚁语言 YiLang

**用中文写代码，在手机上编译、运行。**

蚁语言是一款运行在 Android 上的中文编程语言与 IDE。它把中文语法转译成 C++，并内置完整的 clang 编译工具链——装好 App 就能离线写代码、编译、看结果，不需要电脑，也不需要配环境。

## 代码长什么样

```蚁语言
函数 整数 加法(整数 a, 整数 b) {
    返回 a + b
}

函数 空 交换(整数 引用 a, 整数 引用 b) {
    整数 t = a, a = b, b = t,     // 引用参数：真正修改外面的变量
}

整数[] 成绩 = { 90, 85, 77 }
追加(成绩, 95)                      // { 90, 85, 77, 95 }
排序(成绩)                          // { 77, 85, 90, 95 }
输出(加法(3, 7))                    // 10
```

顶层直接写的语句就是主程序，不用定义 main。关键字全部来自日常用词：如果、对于、函数、返回、并且、或者……打开 App 内置的语法手册和速查，零基础也能上手。

## 功能特性

- **纯中文语法**——类型、控制流、函数、运算全部用中文表达，变量名也可以是中文
- **本地编译运行**——内置 clang++ 工具链，首次启动自动安装，之后全程离线可用
- **报错看得懂**——编译报错自动映射回中文源码的行和列，直接点过去就能改
- **AI 助手**——写代码卡住了可以直接问，AI 能看到你的代码和报错信息
- **项目管理**——多项目、多文件管理，侧边文件树，代码高亮、撤销重做一应俱全
- **新手友好**——首次启动有逐步引导，手册里每个语法都带可运行的例子

## 技术实现

- Kotlin + Jetpack Compose（Material 3）+ sora-editor
- 手写转译器：Lexer → Parser → CodeGenerator，三步把中文代码转译成 C++ 源码
- 内置 clang++（从 Termux 工具链包解包安装），支持预编译头加速
- targetSdk 28 是刻意选择：Android 10 起，targetSdk ≥ 29 的应用禁止执行应用数据目录中的可执行文件（W^X 限制），本机编译这条路线必须低于 29（Termux 同理）

## 项目结构

```
app/src/main/java/com/yilang/
├── transpiler/     蚁语言 → C++ 转译器（Lexer / Parser / CodeGenerator）
├── compiler/       工具链下载安装与编译服务
├── ai/             AI 助手客户端
└── ui/             界面
    ├── editor/     代码编辑器、文件树、输出终端、语法速查
    ├── projects/   项目列表与管理
    ├── manual/     语法手册
    ├── forum/      论坛页（占位，待开发）
    └── profile/    「我的」页（占位，待开发）
```

## 构建与运行

用 Android Studio 打开项目根目录，同步后直接运行即可；命令行方式：

```bash
./gradlew assembleDebug
```

- 最低支持 Android 8.0（minSdk 26）
- release 签名信息从 `local.properties` 读取（不入库），需要配置以下四项才会启用 release 签名：

```properties
yilang.storeFile=你的签名文件路径
yilang.storePassword=密码
yilang.keyAlias=别名
yilang.keyPassword=密码
```

## 参与贡献

**论坛页与「我的」页目前是占位界面**，正在寻找一起完善它们的朋友：

- 论坛页：[ForumPlaceholder.kt](app/src/main/java/com/yilang/ui/forum/ForumPlaceholder.kt)
- 我的页：[ProfilePlaceholder.kt](app/src/main/java/com/yilang/ui/profile/ProfilePlaceholder.kt)
- 导航结构：[MainNavHost.kt](app/src/main/java/com/yilang/ui/main/MainNavHost.kt)（底部「项目 / 论坛 / 我的」三个标签）

欢迎提交 Issue 和 Pull Request！
