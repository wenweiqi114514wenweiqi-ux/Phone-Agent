# Phone Agent — 命令白名单映射明细 v2

> 用途：执行层（Shizuku）只允许执行本表中已注册的命令。
> 所有命令均由工具注册表渲染（参数经注入阻断清洗后填入模板），禁止未注册命令进入执行层。
> 阻断字符：`;` `|` `&` `>` `<` `` ` `` `$` `\` 换行 回车
> 例外工具（跳过过滤，高危确认兜底）：shell_exec / file_write / file_append / http_post / curl_exec

## 命令映射表（共 51 个工具）

### 应用管理

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| pm_list_packages | `pm list packages` | 低 | 列出已安装应用包名 |
| pm_uninstall | `pm uninstall {pkg}` | **高** | 卸载应用 |
| pm_install | `pm install -r {path}` | **高** | 安装 APK |
| pm_clear | `pm clear {pkg}` | **高** | 清除应用数据 |
| pm_disable | `pm disable-user {pkg}` | **高** | 禁用应用 |
| am_start | `am start -n {component}` | 低 | 启动应用/界面 |
| am_force_stop | `am force-stop {pkg}` | **高** | 强制停止应用 |
| am_broadcast | `am broadcast -a {action}` | 低 | 发送广播 |

### 截图与输入

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| screencap | `screencap -p {path}` | 低 | 截屏保存 |
| input_keyevent | `input keyevent {keycode}` | 低 | 模拟按键 |
| input_text | `input text {text}` | 低 | 输入文本 |
| input_tap | `input tap {x} {y}` | 低 | 点击坐标 |
| input_swipe | `input swipe {x1} {y1} {x2} {y2} {duration}` | 低 | 滑动手势 |
| input_longpress | `input swipe {x} {y} {x} {y} 1000` | 低 | 长按（1秒） |

### 文件操作

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| file_list | `ls -1 {path} \| head -300` | 低 | 列目录（单列+总数） |
| file_mkdir | `mkdir -p {path}` | 低 | 创建目录 |
| file_touch | `touch {path}` | 低 | 创建空文件 |
| file_copy | `cp -r {src} {dst}` | 低 | 复制文件/目录 |
| file_move | `mv {src} {dst}` | 低 | 移动/重命名 |
| file_cat | `head -200 {path}` | 低 | 查看文件前200行 |
| file_find | `find {path} -name "{name}" \| head -100` | 低 | 搜索文件 |
| file_chmod | `chmod {mode} {path}` | **高** | 修改权限 |
| file_remove | `rm -rf {path}` | **高** | 删除文件/目录 |
| file_write | `cat > "{path}" << EOF ... EOF` | **高** | 写文本（覆盖） |
| file_append | `cat >> "{path}" << EOF ... EOF` | 低 | 追加文本 |
| file_grep | `grep -rn "{pattern}" "{path}" \| head -50` | 低 | 搜索内容 |
| file_tail | `tail -n {lines} "{path}"` | 低 | 查看末尾 |
| file_stat | `ls -la "{path}"` | 低 | 文件详细信息 |
| file_du | `du -sh "{path}"` | 低 | 占用空间 |
| file_wc | `wc "{path}"` | 低 | 统计行数/字数 |

### 文档处理

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| doc_extract_text | `unzip -p "{path}" word/document.xml \| sed 's/<[^>]*>//g'` | 低 | docx 提取文本 |
| file_base64 | `base64 "{path}" \| head -50` | 低 | base64 编码 |

### 系统设置

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| settings_get | `settings get {namespace} {key}` | 低 | 读取设置 |
| settings_put | `settings put system {key} {value}` | **高** | 修改 system 设置 |
| settings_put_global | `settings put global {key} {value}` | **高** | 修改 global 设置 |

### 系统信息

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| dumpsys | `dumpsys {service}` | 低 | 系统服务信息 |
| logcat | `logcat -d -t 200` | 低 | 最近日志 |
| sys_df | `df -h` | 低 | 磁盘空间 |
| sys_free | `free -h` | 低 | 内存信息 |
| sys_ps | `ps -A \| head -100` | 低 | 进程列表 |
| sys_getprop | `getprop {key}` | 低 | 系统属性 |
| sys_ip | `ip addr show` | 低 | 网络接口 |
| sys_battery | `dumpsys battery` | 低 | 电池状态 |

### 网络与 HTTP

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| http_get | `curl -sL --max-time 15 "{url}"` | 低 | HTTP GET |
| http_post | `curl -sL -X POST -H "Content-Type: application/json" -d '{body}' "{url}"` | 低 | HTTP POST JSON |
| curl_exec | `curl -sL --max-time 20 {args}` | **高** | 通用 curl |
| ping | `ping -c 4 {host}` | 低 | 网络连通性 |
| netstat | `netstat -tlnp` | 低 | 网络连接 |
| dns_lookup | `nslookup {domain}` | 低 | DNS 解析 |

### 设备控制与通用

| 工具名 | 命令模板 | 风险 | 说明 |
|---|---|---|---|
| reboot | `reboot` | **高** | 重启设备 |
| shell_exec | `{command}` | **高** | 任意 shell 命令 |

## 参数约束（执行前校验）

| 参数 | 规则 |
|---|---|
| pkg / component / service / action | 仅允许字母、数字、点、斜杠、下划线、中划线 |
| keycode / x / y / lines / duration | 仅允许纯数字 |
| path / text / value / url | 允许一般字符；**剥离全部阻断字符** |
| file_write / file_append 的 content | 跳过过滤（heredoc 安全），高风险确认 |
| shell_exec / curl_exec / http_post 的参数 | 跳过过滤，高风险确认 |
| 任何参数 | 长度上限 512 字符（file_write content 除外），防超长注入 |

## Binder 传输限制

- ShellUserService 输出截断：50 万字符（Binder 缓冲区约 1MB，超出会 TransactionFailed）
- 大输出应在命令端用 head/tail 分页

## 降级策略
- Shizuku 未激活：shell 类工具全部不可用，UI 提示"受限模式"
- 安全三档：ALLOW_ALL（全执行）/ CONFIRM_HIGH（高危确认）/ CONFIRM_ALL（全部确认）
