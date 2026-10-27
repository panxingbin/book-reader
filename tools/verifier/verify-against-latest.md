# 用当前 IDE 跑 Marketplace 那套弃用校验

## 为什么需要它

Marketplace 上传时跑的是**当前** IDE 的兼容性校验，弃用标注只出现在**新版**平台上。
本构建目标是 2023.2（`since-build="231"`），所以 `run-verifier.ps1` 默认校验的那个 IDE
**永远看不到弃用问题**——3.5.3 就是这样：本地 2023.2 校验 PASS，上传后被拒
（2 处 `createSingleLocalFileDescriptor` + 1 处 `ActionUtil.invokeAction` + 1 处
`FileSaverDescriptor` 构造函数，共 4 处弃用引用）。

同一份 verifier-cli 对着新 IDE 跑一遍，就能提前拦住这类问题。

## 做法

```bash
# 1) 打包
gradle buildPlugin

# 2) 拿一份当前 IDE（下面两种来源任选）
#    a) 本机已装的 IDEA（推荐：免下载，935 MB 的捆绑插件会按需拉取）
#    b) Gradle 解析的发行版

# 3) 跑校验（约 10 分钟，需要能访问 plugins.jetbrains.com；本机走公司代理）
java "-Duser.home=<可写目录>" \
     -Dhttps.proxyHost=10.76.213.80 -Dhttps.proxyPort=808 \
     -jar <verifier-cli-*-all.jar> check-plugin \
     build/distributions/book-reader-<版本>.zip "<IDE 根目录>"
```

verifier-cli 的位置（由 Gradle 插件的 `runPluginVerifier` 任务下载）：

```bash
find .gradle-home/caches -name "verifier-cli-*-all.jar" | grep -v sources
```

`-Duser.home` 必须指到工作区内：默认的 `~/.pluginVerifier` 在工作区外，会被沙箱拒绝。

## 本机已验证

| 产物 | IDE | 结果 |
|---|---|---|
| `book-reader-3.5.3.zip` | IU-262.10968.63（IDEA 2026.2） | **4 usages of deprecated API** —— 与 Marketplace 的驳回信息逐条对上 |
| `book-reader-3.5.4.zip` | IC-232.8660.185（构建目标） | Compatible |
| `book-reader-3.5.4.zip` | IU-262.10968.63（IDEA 2026.2） | Compatible |

3.5.3 在 2026.2 上被点名的四处，正是本次修掉的四处：

```
FileChooserDescriptorFactory.createSingleLocalFileDescriptor()   x2  (ReaderController)
ActionUtil.invokeAction(AnAction, DataContext, String, ...)      x1  (ReadMenuPanel)
FileSaverDescriptor.<init>(String, String, String[])             x1  (SaveHtmlAction)
```

## 噪声 vs 信号

新 IDE 的校验输出里有一部分是环境问题，不用管：

- `Layout component '...' has some nonexistent 'classPath' elements` —— 捆绑插件目录布局，无害；
- `Unable to fetch ... https://jb.gg/ij-api-changes-raw` —— 代理挡住了 JetBrains 的 API 变更页，
  后果只是“文档化的兼容性豁免”没加载；
- 首跑会下载约 1 GB 捆绑插件（`plugins.jetbrains.com`），之后走缓存。

**只看 verdict 那一行**（`Finished 1 of 1 verifications ...: Compatible`），以及它后面
`Deprecated API usages (N)` 的清单。
