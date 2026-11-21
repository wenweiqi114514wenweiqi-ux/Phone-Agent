// ShellUserService AIDL：运行在 Shizuku 进程，提供 shell 命令执行能力
package com.phoneagent.exec;

interface IShellService {
    String exec(String command);
}
