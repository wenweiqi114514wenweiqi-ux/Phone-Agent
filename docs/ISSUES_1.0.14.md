# PhoneAgent 1.0.14 待修问题清单

> 记录时间：2026-11-21（vc67 之后）。按用户反馈逐条登记，修完勾掉。

## 1. 跨对话「串」——必现

用户描述：不同对话之间内容会串，不是偶现，一定复现。

已排查（代码现状）：

- `MessageDao.observeByConversation` / `allByConversation` 都带 `WHERE conversationId = :conversationId`，DAO 层没有漏过滤。
- `ChatScreen` 用 `viewModel(key = "chat_$conversationId")`，理论上每个会话独立 VM。
- `MainScreen` 的 `conversationId` 是 `rememberSaveable`，`ChatScreen` 挂在 `AnimatedContent` 的 `SaveableStateProvider("page_$index")` 下，key 只按 tab index，不按会话。

待确认的两个方向：

- **A（消息层）**：会话切换时旧 VM 仍活在 Activity 的 ViewModelStore 里，`viewModelScope` 未取消；旧会话的流式/工具事件仍在跑并写库。若 UI 侧有任何全局事件总线（`AppEvents` / `IssueNotice`）被新会话订阅，就会出现「B 页看到 A 的内容」。
- **B（上下文层）**：`MemoryRepository.recall()` 每次注入最多 6 条全局记忆。`conversation_summary` 已被过滤，但 `user_note` / `user` / `memory_save` 写入的条目是跨会话全局的。用得越久命中越多，新对话一开口就提旧话题 → 体感就是「串」。

复现取证方式：分别开 A/B 两个会话，A 发一条带特征词的消息，不切走、直接在 B 里发任意消息；同时看 `message` 表里该条消息的 `conversationId` 是否写成 B（消息层 bug）还是 AI 回复里主动提到 A 的内容（上下文层 bug）。

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
