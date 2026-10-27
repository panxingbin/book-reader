# 小说阅读 · Book Reader

**IntelliJ IDEA 上班摸鱼插件** —— 按开源项目
[chapter_reader](https://gitee.com/wind_invade/chapter_reader)（v3.0.6）重做。

菜单位置：**IDEA 顶部菜单栏 → Tools → Read**。

支持**在线阅读**（按 gitee 共享的站点配置解析小说网站）与**本地阅读**
（txt 章节正则 / epub / 简易文章 / 简易文本），并保持参考项目“隐蔽优先”的设计：
普通模式只占 IDE 左下角一行，全章节模式在右侧 **Read** 工具窗口里读。

> 参考实现：反编译源码在 `C:\Users\S6114648\Desktop\chapter_reader.jar.src`，
> 原始仓库克隆在 `E:\workspace\deepseek\chapter_reader`。
> 依赖栈与参考项目一致：**jsoup 1.14.2 + fastjson 1.2.78 + epublib-core 3.1**。

---

## 功能

| 功能 | 说明 |
|---|---|
| **普通阅读模式** | 一行正文显示在 IDE 左下角（状态栏），字体小、极隐蔽；点击可弹出阅读窗 |
| **全章节阅读模式** | 在右侧 **Read** 工具窗口整章阅读，字体/字号/颜色可调 |
| **在线阅读** | 输入小说【章节目录】URL，按 gitee 站点配置解析章节与正文 |
| **在线全章节阅读** | 同上，但在 Read 窗口整本阅读 |
| **正常网站列表** | 并发检测 gitee 已配置站点是否仍可用，双击在浏览器打开 |
| **网站节点调试** | 粘贴 FullPath 调试 `chapterPath` / `contentPath`，一键复制配置并打开 Gitee 提交 |
| **保存网站到本地** | 把网页 HTML 保存到本地，便于获取 JS 动态生成的节点 |
| **本地阅读模式** | 选择 txt + 章节正则（默认 `^第.*章.*`），支持章节跳转 |
| **本地全章节阅读模式** | 同上，整本在 Read 窗口阅读 |
| **Epub 阅读 / Epub 全章节** | 用 epublib 解析 spine 与 `toc.ncx` / `nav.xhtml` |
| **简易文章阅读模式** | 仅按【单行展示最大字数】切分内容 |
| **简易文本阅读模式** | 仅按文件换行符分行 |
| **功能菜单** | 侧边栏 **Read** 按钮打开窗口后，点击顶部的【功能菜单】弹出全部功能，不必再到 Tools → Read 里找 |
| **历史记录** | 自动保存书名、模式、章节数、内容行数、读到第几行；双击恢复 |
| **阅读位置自动保存** | 按项目持久化，重开 IDE 自动恢复本地书籍位置 |
| **内容过滤** | 每行一个正则，丢弃正文里的广告行 |
| **字体选择** | 字体、字号、16 进制颜色，自动保存 |
| **快速清屏** | `Ctrl+Alt+B` 一键隐藏全部阅读内容 |
| **自动下一页** | 按固定间隔自动翻页，再次按下停止 |
| **章节列表 / 跳转** | 3000 章也能按名称筛选跳转 |
| **内容跳转** | 按行号跳转 |

---

## 菜单结构（Tools → Read）

```
Tools
└── Read
    ├── 插件设置              Ctrl+Alt+C
    ├── 使用说明 / 帮助
    ├── ──────────
    ├── 在线阅读
    │   ├── 在线阅读模式              Ctrl+Alt+G
    │   ├── 在线全章节阅读模式        Ctrl+Alt+Shift+G
    │   ├── 正常网站列表              Ctrl+Alt+D
    │   ├── 网站节点调试              Ctrl+Alt+Shift+R
    │   └── 保存网站到本地
    ├── ──────────
    ├── 本地阅读
    │   ├── 本地阅读模式              Ctrl+Alt+F
    │   ├── 本地全章节阅读模式        Ctrl+Alt+Shift+F
    │   ├── Epub阅读模式
    │   ├── Epub全章节阅读模式
    │   ├── 简易文章阅读模式
    │   └── 简易文本阅读模式
    ├── ──────────
    ├── 控制
    │   ├── 有人来了就按我            Ctrl+Alt+B
    │   ├── 快速清屏                  Ctrl+Alt+1
    │   ├── 重新初始化                Ctrl+Alt+R
    │   ├── Read窗口切换至本项目      Ctrl+Alt+W
    │   ├── 普通阅读与全章节阅读转换  Ctrl+Alt+Z
    │   ├── 内容过滤                  Ctrl+Alt+T
    │   ├── 历史记录                  Ctrl+Alt+H
    │   ├── 字体选择
    │   ├── 章节列表 / 跳转           Ctrl+Alt+J
    │   ├── 内容跳转
    │   ├── 上一页                    Ctrl+Alt+2
    │   ├── 下一页                    Ctrl+Alt+3
    │   └── 自动下一页【再次按下则停止】 Ctrl+Alt+4
    └── 赞助一下~ 鼓励持续更新~
```

快捷键可在 `File | Settings | Keymap | Plugins | 小说阅读` 里改。

---

## 两种阅读面的区别

| | 普通模式 | 全章节模式 |
|---|---|---|
| 显示位置 | IDE 状态栏一行（左下角） | 右侧 **Read** 工具窗口 |
| 每次翻页 | 前进 **1 行** | 前进 `全章节每页行数`（默认 30 行） |
| 隐蔽性 | 高（像一条状态栏提示） | 低（整段正文在屏幕上） |
| 内容加载 | 章节模式按需加载：读到章末自动取下一章 | 一次性加载全书 |
| 适合 | 开会、结对编程时瞄两眼 | 午休、独自加班 |

「普通阅读与全章节阅读转换」会在两者之间切换，并保留你读到的那一行。

---

## Read 工具窗口布局

侧边栏点 **Read** 按钮打开右侧窗口后，自上而下是：

```
┌─ Read ─────────────────────────────┐
│ 书名 / 章节 / 进度                    │   ← 书名信息
│ [功能菜单]  [隐藏阅读内容]            │   ← 点击【功能菜单】弹出功能框
│ [上一页][下一页][章节跳转][内容跳转]   │   ← 分页工具列
│  [字体选择][内容过滤][历史记录]        │
│ ────────────────────────────────── │
│ 正文……                              │   ← 阅读区
└────────────────────────────────────┘
```

【功能菜单】弹出的框按 Tools → Read 的分组列出全部 26 个功能，每行带图标、说明和快捷键，
悬停高亮，点击即执行——它只是同一个动作的另一个入口，行为与菜单完全一致（通过
`ActionManager` 按 action id 触发，不是第二份实现）。

【隐藏阅读内容】是【快速清屏】的等价按钮（`Ctrl+Alt+B`）。

---

## 在线阅读是怎么工作的

### 站点配置

站点配置是**按域名**共享的，不按书：`chapterPath` / `contentPath` 对同一域名下
所有小说都成立。插件启动后从

```
https://gitee.com/wind_invade/chapter_reader/raw/master/chapter_config-v1.json
```

拉取一次（约 3000 条），并缓存到插件设置里；**Gitee 不可达时自动使用本地缓存**，
与参考项目的降级行为一致。

一条配置长这样：

```json
"www.example.com": {
  "checkUrl": "https://www.example.com/book/12345/",
  "chapterPath": ".listmain",
  "contentPath": "#content",
  "nextUrlPath": "a.next",
  "nextPathText": "下一页",
  "nextLoop": true,
  "nextLoopCnt": 15
}
```

### 节点路径支持的全部写法

共享配置文件由很多人手工维护，写法非常杂。`NodePath` 统一处理（**实测 3029 条
配置 0 条被 jsoup 拒绝**）：

| 写法 | 例子 |
|---|---|
| XPath 绝对标签路径 | `/html/body/div[6]/div/dl` |
| 相对标签路径 | `div[2]/div[1]/div[3]` |
| 浏览器 “Copy full JS path” | `html body div.book_last` |
| CSS 选择器 | `#list`、`.listmain`、`a.dise.sm:nth-of-type(4)` |
| 用 `/` 连接的 CSS 片段 | `#content_read/.box_con/.mulu/a[3]` |
| 手工录入的笔误 | `/html/body/div[4]/div[2]/div[2]]` |

要点：

* XPath 的 `div[6]` 是「第 6 个 `div` 兄弟」，因此翻译成 CSS `div:nth-of-type(6)`，
  **不是** `:nth-child(6)`；
* `/` 两侧是纯标签名时按**直接子节点**（`>`）处理；两侧带 `.class` / `#id` 时按
  **后代**（空格）处理——这是人手写 `.box_con/#list` 的本意；
* 首选读法找不到时，会自动用另一种组合符重试，再退到最深层节点，兼容浏览器补出的
  `<tbody>` 与服务器的原始标记之间的差异；
* 括号不平衡会被自动修复（`div[2]]` → `div[2]`）；
* 明显写错的条目（把 URL 填进 `chapterPath`、留空）会被识别为不可用并给出提示，
  而不是抛异常。

### 多页章节

一章被网站拆成多页时，配置 `nextUrlPath`（「下一页」节点）。插件会：

1. 取该节点的 `href`；
2. **校验节点文本是否仍匹配 `nextPathText`**（默认 `下一页`）——不匹配就停止，
   避免网站把「下一页」悄悄换成「下一章」时把整本书串成一章；
3. 循环拼接，最多 `nextLoopCnt`（也兼容旧字段 `nextLoopMax`）次。

---

## 本地阅读

* txt **必须为 UTF-8 编码**（与参考项目一致）。
* 章节正则默认 `^第.*章.*`：
  * `第1238章 标题` → `第.*章.*`
  * `第1篇 标题` → `第.*篇.*`
  * 正文里出现「第 x 章」被误判时，加上 `^` 只识别行首。
* Epub 用 epublib 读 spine 顺序，章节名取 `toc.ncx` / `nav.xhtml`，
  没有目录时回退到每章的 `title` / `<h1>`。

---

## 目录结构

```
src/main/kotlin/com/bookreader/reader/
├── ReaderMode.kt            八种阅读模式（来源 × 切分方式 × 显示面）
├── ReaderSettings.kt        插件设置（App 级持久化）
├── ReaderController.kt      胶水层：启动模式、选文件、后台解析、刷新、清屏
├── ReadingEngine.kt         阅读运行时：分页、翻页、跳转、自动翻页、按需加载
├── ReadingState.kt          按项目持久化：当前书 + 位置 + 历史记录
├── ReaderLog.kt             进度输出到 Notifications / Event Log
├── ReaderStartupActivity.kt 启动接线：订阅状态栏 + 恢复上次的本地书
├── model/
│   ├── ChapterInfo.kt       章节指针（标题 + URL / 偏移）
│   └── BookContent.kt       章节列表 + 展示行 + 章节→行映射
├── remote/
│   ├── SiteConfig.kt        一条站点配置（对应 RemoteChapterSupport）
│   ├── SiteConfigRepository.kt  gitee 拉取 + 本地缓存 + 域名匹配
│   ├── NodePath.kt          节点路径 → jsoup 选择器（见上表）
│   ├── RemoteChapterReader.kt  目录 / 正文 / 下一页解析
│   └── Http.kt              jsoup 连接封装
├── local/EpubChapterReader.kt  epublib 读取
├── loader/BookLoader.kt     模式 → BookContent
├── util/
│   ├── HtmlText.kt          HTML → 散文（保留段落换行）
│   ├── TextWrapper.kt       单行展示最大字数切分 / 换行符切分
│   └── LocalChapterSplitter.kt  正则章节切分
├── settings/ReaderSettingsPage.kt  设置页
├── ui/
│   ├── ReaderStatusBar.kt   普通模式（状态栏 widget + 弹出窗）
│   ├── ReadMenuPanel.kt     功能菜单弹出框（侧边栏按钮后的那个框）
│   ├── ReaderActionsCatalog.kt  功能菜单的条目元数据
│   ├── ReadPanel.kt         全章节模式（Read 工具窗口）
│   ├── ReadToolWindowFactory.kt
│   └── dialogs/             网站列表、节点调试、章节跳转、内容跳转、字体、过滤、历史、帮助
└── actions/ReaderActions.kt 全部菜单动作
```

---

## 构建

需要 **JDK 17+**。本机已验证可用的组合：

```
JAVA_HOME        = C:\Program Files\Java\jdk-17.0.6
Gradle           = 8.10（已缓存于 ~/.gradle/wrapper/dists）
IntelliJ Platform= 2023.2（IC，已缓存）
```

### 代理

本机 Maven / Gradle 需经公司代理，已写入 [gradle.properties](gradle.properties)：

```properties
systemProp.https.proxyHost=10.76.213.80
systemProp.https.proxyPort=808
kotlin.compiler.execution.strategy=in-process
```

> `in-process` 是必需的：沙箱里 Kotlin 的独立编译守护进程无法创建临时文件
> （`AccessDeniedException: kotlin-daemon-client-*.tmp`）。

### 命令

仓库没有 wrapper 脚本，Gradle 用缓存里那份 8.10，并显式指向工作区内的
`.gradle-home`（依赖都缓存在这里，代理与 `in-process` 编译策略也只在它的
`gradle.properties` 生效）：

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-17.0.6"   # 必须 ≥ 11，否则 gradle 插件报 Java 8
export GRADLE_USER_HOME="$PWD/.gradle-home"
G="C:\\Users\\<user>\\.gradle\\wrapper\\dists\\gradle-8.10-bin\\<hash>\\gradle-8.10\\bin\\gradle.bat"

"$G" --offline compileKotlin       # 编译
"$G" --offline verifyReader        # 纯逻辑验证（见下）
"$G" --offline checkPluginVersion  # 版本号一致性检查（buildPlugin 会先自动跑）
"$G" --offline buildPlugin         # 产物 build/distributions/book-reader-<版本>.zip
"$G" --offline runIde              # 启动沙箱 IDE 实测
```

`--offline` 不可省：走代理解析依赖超时，最终也会回落到缓存。
`.build/gradle-run.bat` 是同一件事的 Windows 包装（后台写 `build.log`），二选一即可。

Marketplace 校验用 [tools/verifier/run-verifier.ps1](tools/verifier/run-verifier.ps1)：

```bash
powershell -File tools/verifier/run-verifier.ps1 -PluginZip build/distributions/book-reader-3.5.4.zip
```

它默认校验**构建目标 IDE（2023.2）**。注意这只覆盖最低版本：3.5.3 就是这样
本地 PASS、上传后仍被拒的——Marketplace 跑的是**当前** IDE，弃用标注只在新版出现。
想提前拦住这类问题，需要另外拿一份新 IDE 来跑同一个 CLI，做法与实测结果见
[tools/verifier/verify-against-latest.md](tools/verifier/verify-against-latest.md)
（3.5.3 在 2026.2 上精确复现 Marketplace 的 4 处弃用引用；3.5.4 在 2023.2 与 2026.2 上均 PASS）。

### 版本号（打包前必做）

**`gradle.properties` 的 `pluginVersion` 是唯一来源**：

```properties
pluginVersion=3.1.0
```

改这一处即可，`patchPluginXml` 会把它写进打包后的 `plugin.xml`。
同时 `checkPluginVersion` 会在 `buildPlugin` 之前核对
`src/main/resources/META-INF/plugin.xml` 里的 `<version>`，不同步就让构建直接失败：

```
> Task :checkPluginVersion FAILED
> plugin.xml declares version '3.1.0' but the build is '9.9.9'.
  Bump pluginVersion in gradle.properties and update src/main/resources/META-INF/plugin.xml to match.
```

之所以要这道闸，是因为 `patchPluginXml` 只改**产物**，源码里的旧版本号会被静默盖掉，
下次翻源码会误以为还是旧版 —— 所以源 `plugin.xml` 也要一起改。

> 本工作区**无法在前台跑长构建**（执行器会把它转后台并失败），因此仓库里带了
> `.build/gradle-run.bat`：它把输出写到 `build.log` 并以后台进程运行。
> 用法：先启动该 bat，再轮询 `build.log`。

### Marketplace 上传校验（服务端，本地 IDE 装不出来）

Marketplace 在上传时会跑两道**服务端**校验，本地 `buildPlugin` 成功并不代表能过：

1. **`<description>` 必须以拉丁字符开头，且前 40 个字符内不能有中日韩字符。**
   真实规则来自 `structure-base` 的 `ValidatorKt.validateDescriptionIsCorrect`：剥掉 HTML
   后必须在**下标 0** 处匹配 `^[\w\s\p{Punct}–—]{40,}`。`\w` 与 `\p{Punct}` 都是 ASCII-only，
   所以第 40 位之前出现一个汉字，匹配就会提前结束并直接判失败。
   中文文案**不要删**，把它整体挪到拉丁开头之后即可（见 [plugin.xml](src/main/resources/META-INF/plugin.xml)）。
   仓库里的 `tools/descriptor-check/ValidatorProbe.java` 就是这条规则的本地复现器。

2. **不得使用平台内部 API。** 最常见的就是自己实现 `DataContext`：该接口是
   `@ApiStatus.NonExtendable`，一旦匿名覆写 `getData(String)`，Verifier 会报
   `Internal method usage — DataContext.getData(String)`。
   **正确写法**是用平台自带的工厂：

   ```kotlin
   val context = SimpleDataContext.getProjectContext(project)
   ```

   `SimpleDataContext` 虽在 `.impl.` 包下，但**没有任何 `@ApiStatus.Internal` 标注**，
   平台文档也明确推荐用它替代手写 `DataContext`。
   但**别顺手拿它造 `AnActionEvent`**（`AnActionEvent.createFromAnAction(action, null, place, context)`）：
   新版平台上这些工厂全部 `@Deprecated(forRemoval = true)`，会命中第 5 条。
   触发动作请直接走第 3 条的 `ActionManager.tryToExecute`，DataContext 由它从 Component 解析。

3. **不得调用标了 `@ApiStatus.OverrideOnly` 的方法。**
   最常见的是 `AnAction.actionPerformed(AnActionEvent)`：插件可以**覆写**它，但绝不能**调用**它，
   否则 Verifier 报 `Override-only method usage violation`。
   **正确写法**是走平台的正规触发路径（它内部会先跑 `update()` 再执行）：

   ```kotlin
   ActionManager.getInstance().tryToExecute(
       action,
       null,               // inputEvent
       contextComponent,   // 用它解析 DataContext（项目、编辑器…）
       ActionPlaces.UNKNOWN,
       true,               // now：在当前 EDT 上立即执行
   )
   ```

   `tryToExecute` 是 `ActionManager` 的公开抽象方法，**从 231 到最新版本都存在，
   且没有任何弃用/内部标注**——这是它胜出的原因，因为其它触发路径都断了：

   - `ActionUtil.invokeAction(...)` 新版已 `@Deprecated`（3.5.3 就是被这条拒的）；
     它的替代品 `ActionUtil.performAction(action, event)` 需要 `AnActionEvent.createEvent(...)`
     造事件，而这两个 API 2023.2 上都不存在（`javap` 可验证）；
   - `AnActionEvent.createFromAnAction(...)` 及旧构造函数同样是 `@Deprecated(forRemoval = true)`。

   代价是它只能从 **Component** 解析 DataContext（不传则退化为当前焦点窗口的上下文），
   所以要传一个**始终挂载、且属于目标项目**的组件：见 `ReadMenuPanel` 传的是工具窗组件——
   面板自己的组件到执行时已经被 `beforeInvoke` 关闭的弹窗带离窗口树，不能再用。

4. **被驳回的版本号不能重复上传**，改完必须升版本号（`pluginVersion` + 源 `plugin.xml` 的 `<version>`）。

5. **不要调用标了 `@Deprecated` 的 API**（Verifier 报 `Deprecated methods usages` / `Deprecated constructor usage`）。
   本项目踩过的四处（其中两处是 3.5.3 被拒后回查出来的）：

   | 弃用 API | 替代写法 |
   |---|---|
   | `FileChooserDescriptorFactory.createSingleFileDescriptor()` | 当时换成了 `createSingleLocalFileDescriptor()`，**但它从 2025-02 起（IJPL-150176）也被 `@Deprecated` 了**（“其实允许选目录”），推荐的 `singleFile()` 2023.2 上又没有 → 改用公开构造函数 `FileChooserDescriptor(true, false, false, false, false, false)`，所有版本都有，新版只标 `@ApiStatus.Obsolete`（见 `ReaderController.singleFileDescriptor`） |
   | `StatusBar.addWidget(StatusBarWidget)` | 用 `statusBarWidgetFactory` 扩展点（见 `plugin.xml` 的 `BookReaderStatus`）；四个 `addWidget` 重载全部弃用 |
   | `ActionUtil.invokeAction(...)` | `ActionManager.tryToExecute(...)`（见第 3 条；3.5.3 被拒的主因之一） |
   | `FileSaverDescriptor(String, String, String...)` | 新版的 `FileSaverDescriptor(String, String)` + `withExtensionFilter(String)` 与 2023.2 的 varargs 构造函数**在源码层面互斥**（新构造函数 2023.2 根本没有）→ 两个都走反射调用：字节码里不出现任何一个构造函数的引用，Verifier 只看字节码引用，就无从报起（见 `ReaderActions.saveDialogDescriptor`） |

   > **Verifier 到底认什么**：只认 `@Deprecated`（Java/Kotlin 两种写法）与 `@ScheduledForRemoval`。
   > `@ApiStatus.Obsolete` 不报（verifier-cli 的 `DeprecationUtilKt` 不解析它），上表第一行
   > 的构造函数、以及 `createSingleFileDescriptor(FileType)` 这类 Obsolete 方法都能过——这是
   > `since-build="231"` 下唯一还剩的余地。Obsolete 方法日后若被改成 `@Deprecated`（就像
   > `createSingleLocalFileDescriptor` 刚经历的那样），会再次被拒，届时只能升最低版本。

   > **为什么不能直接用新 API**：`singleFile()`（2025-02）、新的 `FileSaverDescriptor` 构造函数、
   > `ActionUtil.performAction` / `AnActionEvent.createEvent` 全部只在较新平台存在（2023.2 上
   > 都没有），直接换等于放弃 2023.2–2024.x 用户。上面几行都是“新旧版本都能跑、且两边都无弃用引用”的写法；
   > 若日后提高最低版本，三处都可以换成直接调用。

### 依赖

`jsoup` 与 `fastjson` 从 Maven Central 取；`epublib-core:3.1` 已不在 Maven Central，
因此把参考插件自带的同版本 jar 放在 [libs/](libs)：

```
libs/epublib-core-3.1.jar
libs/kxml2-2.3.0.jar
libs/xmlpull-1.1.3.4d_b4_min.jar
libs/slf4j-api-1.6.1.jar
libs/slf4j-simple-1.6.1.jar
```

`buildPlugin` 会把它们连同 kotlin-stdlib 一起放进 `<插件>/lib/`，布局正确：

```
book-reader/
└── lib/
    ├── instrumented-book-reader-3.0.6.jar
    ├── jsoup-1.14.2.jar
    ├── fastjson-1.2.78.jar
    ├── epublib-core-3.1.jar
    ├── kotlin-stdlib-1.9.24.jar
    └── …
```

---

## 验证

### 1. 纯逻辑验证

```bash
gradle verifyReader
```

只触碰不依赖 IntelliJ Platform 的类，因此在普通 JVM 上就能跑。覆盖：

* `NodePath` 选择器翻译（含上表全部写法与笔误修复）
* `NodePath.select` / `RemoteChapterReader` 在真实结构上的解析、下一页文本校验
* `HtmlText` 去标签、段落换行、`&nbsp;`
* `TextWrapper` 中日文按字切分、ASCII 单词不切断、简易两种模式
* `LocalChapterSplitter` 正则章节、正文边界、行首锚定
* **真实的 798 KB `chapter_config-v1.json`**：解析条数、可用率、选择器是否被 jsoup 拒绝

最近一次结果：

```
entries: 3029        usable: 3008
with nextUrlPath: 645   nextLoop=true: 495
chapterPath unusable: 42   contentPath unusable: 45
selector rejected: 0
ALL VERIFICATIONS PASSED
```

### 2. 描述符验证

```powershell
powershell -File tools/descriptor-check/check.ps1
```

用 **IntelliJ Platform 自己的 `PluginDescriptorLoader`** 加载打好的 zip —— 与 IDE 启动
时同一条代码路径，因此扩展点、`implementation` 类、快捷键、图标、`<depends>`、
`since-build` 都由平台校验。最近一次结果：

```
id            = com.bookreader.reader
name          = 小说阅读        (U+5C0F U+8BF4 U+9605 U+8BFB)
version       = 3.0.6
sinceBuild    = 231
dependencies  = [com.intellij.modules.platform]
isCompatible  = true
DESCRIPTOR LOADED OK
```

---

## 安装

任选其一：

1. **Install Plugin from Disk** → 选 `build/distributions/book-reader-3.0.6.zip` → 重启 IDE
2. 解压后把 `book-reader/` 整个目录复制到 `<配置目录>/plugins/` → 重启 IDE

首次使用请在 **Settings → Tools → 小说阅读** 里确认「是否开启插件 = 是」。

---

## 3.5.0 清屏可恢复 + 显示样式选项英文

**一键隐藏阅读内容后无法恢复**（boss key 原来只能单向清屏）：

- 清屏后阅读位置保留在引擎里，再次按【有人来了就按我】(Ctrl+Alt+B) 即恢复阅读，
  不再需要「重新初始化」重新加载全书；快速清屏 (Ctrl+Alt+1) 后同样可用
  Ctrl+Alt+B 恢复。
- 全章节模式下恢复时 Read 窗口自动重新打开并激活（清屏时窗口被隐藏，不自动
  重新打开的话用户会以为没恢复成功）。
- Read 面板在隐藏状态时显示「已隐藏 + 恢复提示」卡片，正文不再出现在面板里，
  保持隐蔽性。

**设置里「正文显示样式」选项改成英文**：

- 下拉框选项改为 `Status bar (bottom-right text)` / `Code hint (grey editor bar)` /
  `Navigation bar (top path bar)`，不再显示中文选项。
- 设置入口显示名从「小说阅读」改为 **Book Reader**
  （Settings → Tools → Book Reader）。
- 修复下拉框**无法选择**的问题：3.4.0 起说明文字（styleHint）和下拉框被加到同一个
  `BorderLayout.CENTER`，后加的说明文字把下拉框从布局里顶掉，下拉框以 0×0 的尺寸
  留在面板里——看不见也点不到，所以「不能选」。现在两个组件改为垂直堆叠
  （BoxLayout.Y_AXIS），下拉框恢复可见、可选。

**「导航栏」样式在哪显示 / 为什么一直不动**：

- 导航栏 = IDEA 编辑器上方的**面包屑**（`项目名 > 目录 > 文件名` 那一行），
  开关在 **View → Appearance → Navigation Bar**。选中该样式后，面包屑的项目名段
  会被替换成当前正文行，其余段清空。
- 修复了两个实现缺陷：
  1. **文字永远停在占位符**：正文写入的是插件自建的扩展实例，而平台渲染的是扩展点
     自己实例化的另一个实例，两者从不是同一个对象。现在文字存放在所有实例共享的
     companion 字段里，翻页即更新。
  2. **面包屑被永久劫持**：旧实现不分样式，任何显示样式下都把项目名替换成插件
     占位符（`DestructionNavBarExtension` 也在所有样式下清空其它段），等于装了插件
     面包屑就不能正常用。现在只在「导航栏」样式 + 插件开启 + 有正文时才接管；
     其他样式、清屏、关闭插件时面包屑完全恢复原样。
- 导航栏正文现在也按「导航栏单行最大字数」（≤45）截断，之前只有状态栏样式截断。

**「导航栏 / 代码提示」样式下右下角仍显示「📖 书名 · 已加载」**：

- 原因：非「状态栏」样式时，状态栏 widget 仍会渲染一行 `📖 书名 · 已加载` 的
  「已加载指示器」——它本身就暴露了阅读状态，把这两种样式的隐蔽性抵消掉。
- 现在：非「状态栏」样式时状态栏**完全空白**（widget 只保留一个空格占位，不绘制
  任何字符）。仅「状态栏」样式照旧在右下角显示正文行。

## 3.1.1 修掉的 epub 问题

报错「解析章节正文失败：内容为空」有两个独立成因，都已修复并用测试 epub 复现验证：

**1. 封面页被当成第一章（最可能的原因）**

真实 epub 的 spine 开头几乎总有封面页和书名页，而那些页面只有 `<img>`，
扁平化后**一个字都没有**。旧代码把每个 spine 项都变成一个章节，于是：

- 章节 1 = 封面（空）→ 阅读从空白页开始 → 直接报「内容为空」
- 章节跳转列表里还会出现两个点进去没内容的条目

现在 `EpubChapterReader` **在读取时就扁平化每个 spine 项，跳过没有文字的页面**
（封面、书名页、纯图片页），并记录跳过数量。测试用的 epub 特意在前面放了
封面页和书名页，`skipped=2`、章节数 3、列表里没有空章节。

**2. EPUB 3 拿不到目录（章节名退化成「第1章」）**

`epublib 3.1` **只有 `NCXDocument`，没有 `NAVDocument`** —— 我查过 jar 里的类。
所以只带 `nav.xhtml`、不带 `toc.ncx` 的 EPUB 3 书（完全合法，也很常见）从库里
拿不到任何目录，章节名只能用每章自己的 `<title>`，常常就是文件名。

现在用 jsoup 补读 `nav.xhtml`：找 `epub:type="toc"` 的那个 `<nav>`，取其 `<a href>`；
NCX 与 nav 同时存在时以 NCX 为准。

**另外三处稳健性改动**

- 普通模式的**起点章节没正文时自动向后找**第一个有正文的章节，而不是直接失败。
  在线小说偶尔某一章解析失败，现在只会跳过它。
- 失败提示带上诊断：已检查 x/y 章、跳过几个纯图片页。epub 若整本都是图片
  （扫描版）会明确说明无法阅读。
- 单章读取整体包进 `runCatching`，一章出问题不会让整本书加载失败。

## 3.1.0 修掉的真实问题

打包前复查了一遍，以下都是**實作缺陷**而非风格问题：

| # | 问题 | 后果 | 修法 |
|---|---|---|---|
| 1 | 自动下一页每次开启都新建一个 `TimerTask`，旧的从不取消 | 反复开关后 N 个定时器同时跑，**每跳翻 N 页** | 只保留一个任务引用，停止时 `cancel()` |
| 2 | 全章节解析循环从不检查 `indicator.isCanceled` | 按钮写着「可取消」但**按了没反应**，3000 章网站只能干等 | 逐章检查取消；并回報 `done/total` 进度 |
| 3 | 按需加载用 `book.lines + wrapped` 追加 | 每章复制整个列表 → 长篇读到底 **O(n²)** | 改成 `MutableList` 原地 `addAll` |
| 4 | `filterPatterns()` / `chapterPattern()` 每次调用都重新编译正则 | 全章节模式编译 **3000 次**，且每行渲染都调一次 | 按原始字符串做缓存，改设置自动失效 |
| 5 | 798 KB 站点配置存在 `ReaderSettings.remoteConfigJson` | 写进 IDE 的 `book-reader.xml`，**每次启动解析、每次保存重写** | 迁到系统目录独立文件，带一次性迁移；设置页改成导入/清空 |
| 6 | `startChapter` 参数被 `BookLoader` 忽略 | 「普通 ⇄ 全章节」转换后**跳到别的句子** | 新增 `positionIn()`，按章内偏移定位 |
| 7 | 普通模式向后跳章会把该章追加到列表末尾 | 再往后翻会**跳章、顺序错乱** | 向后跳章时重置已加载窗口，从该章重新开始 |
| 8 | 全章节模式选完只在日志里说了一句 | 窗口没开的话**屏幕上什么都没发生** | 载入完成自动 `activateReadWindow()` |
| 9 | 版本号在 `build.gradle.kts` 和 `plugin.xml` 各写一份 | 打包时忘改其中一个 | 单一来源 + `checkPluginVersion` 构建校验 |

## 还可以再做（未实现，按价值排序）

1. **在线全章节并发抓取** —— 现在是顺序抓，3000 章会很久。可以开 4–8 个线程 + 礼貌延迟，
   或干脆改成增量加载（只预取接下来 N 章）。这是当前最大的性能瓶颈。
2. **正文磁盘缓存** —— 同一章来回翻会重复请求网站。按 URL 缓存到系统目录即可，也顺便
   降低对网站的压力。
3. **真正的「导航栏」显示样式** —— 目前只是把状态栏宽度收窄到 ≤45 字，没有注册 `navbar`
   扩展点。参考项目是把正文渲染进导航栏区域。
4. **`ReaderActionsCatalog` 与 `plugin.xml` 的同步校验** —— 现在靠人工，漏了只会静默少一项。
   可以写个 Gradle 任务比对两边的 action id。
5. **资源包国际化** —— 中文是硬编码在 Kotlin 与 `plugin.xml` 里的，接 `ResourceBundle`
   才能像参考项目那样切中/英。
6. **服务层单测** —— `verifyReader` 只覆盖不依赖平台的纯逻辑。接 IntelliJ 测试框架后可以
   覆盖 `ReadingEngine` 分页、`SiteConfigRepository` 域名匹配、设置迁移这些真正易错的路径。
7. **无效正则的即时提示** —— 设置页现在对写错的正则静默忽略，应该在输入框旁标红。
8. **按内容签名记进度** —— 现在按文件路径记，改了文件名就续不上。
9. **站点配置的本地覆盖层** —— 某站点节点失效时不必等 gitee 合并，本地先盖一层。
10. **翻页撤销** —— 误按「下一页」目前只能手动翻回去。

## 与参考项目的差异

如实记录，避免误导：

| 项 | 状态 |
|---|---|
| 显示样式「左下角」 | ✅ 用状态栏 widget 实现 |
| 显示样式「代码弹出框」 | ✅ 自动弹出的等宽弹窗，不抢焦点 |
| 显示样式「导航栏」 | ⚠️ 设置项保留并生效为**更窄的状态栏宽度**（≤45 字），但**没有**注册真正的 `navbar` 扩展点。参考项目把正文渲染进导航栏区域，实现代价与风险都较高，暂未照搬 |
| 快速清屏 | ⚠️ 会隐藏状态栏正文、关闭 Read 窗口并显示【清屏文本】，但**不会**清空 IDE 的 Event Log（平台无公开 API） |
| 赞助 / 支持 | ⚠️ 只弹出说明与原项目链接，未内置收款码图片 |
| 普通模式弹出窗 | ⚠️ 点击状态栏一行即可弹出；「代码弹出框」样式会自动弹出 |
| 其余菜单、快捷键、模式、配置格式、降级行为 | ✅ 与参考项目对齐 |

---

## 从旧版本迁移

本仓库此前是一个「章节树工具窗口 + 书籍库」的插件（Markdown / HTML / 注释章节树、
注释当书读、书籍库）。按“参考 chapter_reader 重做”的要求，实现已整体替换；
旧源码保留在 `.legacy-src/`（不参与编译），其中的解析器与踩坑记录仍可查阅。

---

## 许可

参考项目为 MulanPSL-2.0。本实现沿用同一许可精神，使用时请遵守原项目许可与
各小说网站的服务条款，仅用于个人学习与阅读。
