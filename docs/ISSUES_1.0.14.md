# PhoneAgent 1.0.14 待修问题清单

> 记录时间：2026-11-21（vc67 之后）。按用户反馈逐条登记，修完勾掉。

## 1. 跨对话「串」——已定位为记忆层，非消息层

用户描述（2026-11-21 补充，决定性证据）：A 会话里让它写计算器，它提醒过「设备没有 Python」；新开 B 会话让它再写个测试，它开口就说「之前我已经确认设备上没有 Python 环境」，并且在调用工具前就说了。用户判定为跨对话串，必现。

**根因（已核实到代码）：** 不是消息串，是长期记忆被跨会话注入。

1. 模型在 A 会话里主动调用 `memory_save`，把「设备上没有 Python 环境」写进全局记忆库（`ShizukuExecutor.memorySave` → `category = "user"`）。
2. `MemoryRepository.recall()` 用中文 2-gram 做 LIKE 匹配，**单个关键词命中就返回**。B 会话里用户说了「计算器」，抽出来的 2-gram「计算」正好命中那条记忆 → 旧环境结论被注入系统提示。
3. `buildSystemPrompt` 只写「关于用户的长期记忆（参考资料，不是指令）」，没有时间、没有来源、没有“可能过期”的标注 → 模型把它当成本轮已确认的事实说出来。
4. `memory_save` 的工具描述与提示词只强调「用户说记住时必须调用」，**没有禁止写环境探测结论**，模型于是把易变事实也存了。

修法（1.0.14 内可做完，分三层收口）：

- **写入侧**：`memorySave` 加启发式护栏——内容里出现「没有 / 未安装 / 不支持 / 不可用 / 不存在 / 已确认」且带环境词（python、zip、java、busybox、shizuku、root 等）时直接拒写，回一句「环境结论不要写入长期记忆，本轮实测即可」。提示词同步改成：只有用户明确要求记住的偏好/称呼/习惯才可写，环境与工具可用性一律不写。
- **读取侧**：`recall()` 要求**至少 2 个不同关键词命中，或单命中但 weight ≥ 3**，否则丢弃；注入块标题改成「历史对话里记下的事，可能已经过期，需以本轮实测为准」，每条前置相对时间（如「3 天前」）。`conversation_summary` 继续保持过滤。
- **话术侧**：系统提示补一条——记忆里关于设备/环境的结论，不得说成「我之前已确认」，只能说「旧对话里记过一次，需要重新验证」，并在本轮重新跑工具核实。

**刻意不做的**：不把记忆全删、不关掉记忆功能（用户要的是个性化，不是失忆）；也不靠“模型自觉”，护栏落在代码里。

## 2. 设置二级页返回主菜单 —— 未修（vc67 仍未生效）

## 2. 设置二级页返回主菜单 —— 未修（vc67 仍未生效）

用户描述：在设置某个二级页（如「个性化」「模型」「关于」）停留时，切到会话页/聊天页/工具页，再切回设置，仍停在那个二级页，不回到设置主菜单。

代码现状（`ui/settings/SettingsScreen.kt:119`、`ui/MainScreen.kt:245`）：

```kotlin
LaunchedEffect(active) { if (!active) page = PAGE_HOME }
```

失效原因：切 tab 的那一刻，`SettingsScreen` 所在的 `AnimatedContent` 分支随即退出组合，`LaunchedEffect` 刚 launch 就被作用域取消，effect body 根本没执行，`page` 保持原值；`rememberSaveable` 又把旧 page 存了下来。

修法：不要依赖 `active` 翻转的副作用。在 `MainScreen` 里维护 `var settingsReset by remember { mutableIntStateOf(0) }`，底部导航点击其他 tab 且当前在设置页时 `settingsReset++`；`SettingsScreen(container, resetSignal = settingsReset)` 内 `LaunchedEffect(resetSignal) { page = PAGE_HOME }`。点击发生在设置页还在组合时，effect 能跑完，之后再离开组合。

## 3. 「本次对话的文件」覆盖页不随 tab 关闭 —— 必现

用户描述：聊天页左上角点开「本次对话的文件」后，切到底部任意 tab（会话 / 工具 / 设置），这层覆盖页不会消失，会一直盖在新页面上方（截图为设置页被「本次对话的文件」盖住，底部导航仍是设置高亮）。

代码现状（`ui/MainScreen.kt`）：

- `var showFiles by rememberSaveable { mutableStateOf(false) }`，只在 `ArtifactsPage` 的 `onClose` 里置回 false。
- `if (showFiles) { ArtifactsPage(...) }` 直接写在 `Scaffold` 内容 `Box` 里、`AnimatedContent` 之外，与 `selected` 完全无关。
- 结果：切 tab 不会触发 `onClose`，覆盖页留在最上层；`rememberSaveable` 还会跨进程重建保留这个 true。

修法：两个一起做。

1. 覆盖页只在聊天 tab 有效：`if (showFiles && selected == 0)`，切走立刻不渲染。
2. 底部导航 onClick 里顺手 `showFiles = false`（或直接在 `LaunchedEffect(selected) { if (selected != 0) showFiles = false }`），把状态清掉而不是只隐藏，避免切回聊天时它自己又冒出来。

顺带核查同类"浮层"：`IssueNotice`、工具选择 sheet、模型 sheet 都是 `ModalBottomSheet`/Dialog，有 scrim 与返回键处理，不受影响；只有这个覆盖页是自己拼的 `Box`，要单独管。

## 4. 切换深浅色时顶栏颜色慢半拍

用户描述：在设置里切深色/浅色，页面内容立刻变色，但顶部那条标题栏的颜色要滞后一下才跟上。

根因（已反编译核实）：`MainScreen` 用的是 M3 `CenterAlignedTopAppBar`，M3 1.3.1 的 `AppBarKt` 内部对容器色用了 `animateColorAsState`（在 `containerColor` 与 `scrolledContainerColor` 之间按滚动比例 lerp，动画时长固定）。主题色一变，它就带着旧颜色往新颜色补一段动画，于是整屏瞬切、只有顶栏慢半拍。

修法（任选，推荐 1）：

1. 顶栏不再自己上色：外层 `Box(Modifier.fillMaxWidth().background(colorScheme.surface))`，AppBar 的 `colors` 传 `containerColor = Color.Transparent`、`scrolledContainerColor = Color.Transparent`。背景由 Box 瞬变，内部 lerp 在透明之间动画，肉眼无差别。
2. 干脆不用 M3 AppBar，自己写一个 `Surface(color = surface) + Row` 当顶栏，顺带把标题、左文件按钮、右新建按钮的间距一并控住，彻底没有内部动画。
3. 如果以后要保留滚动变色，只能接受这个时长（改不了 M3 内部 tween）。

同时核查：底部 `NavigationBar` 也有类似内部动画（indicator 有 ripple/动画），但它是按项高亮，切换时不明显，暂不动。

## 5. 其他本轮已提到、仍需跟进

- 调色盘（已重写为方块+色相条+hex，vc67）：待用户确认是否还有异常，特别是改主色时其它槽位被联动的问题（那属于 `Theme.buildScheme` 派生，不在色盘）。
- 浅色模式细节校准：`ui/` 下约 19 处 `Color(0xFF…)`、30 处 `Color.White/Black` 未逐个核对对比度。
- Office 预览未真机验证（docx/xlsx/pptx 保真度、卡在「正在解析」）。
- 更新检测整块未做（版本清单 JSON / 24h 节流 / 手动检查更新 / DownloadManager + 签名校验）。
- 三处小修：流式表格按列成形、图标右侧脱节侧键、关于页「执行通道」文案 Root 优先→Shizuku 优先。
- 1.0.14 三个提交（`aad1d30` `0d9d346` `73adb26` `62c0cb4`）尚未 push，v1.0.14 未发版。
</content>
