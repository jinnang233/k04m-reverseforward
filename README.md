# K04M Reverse Forward

K04M Reverse Forward 是一个正式的 Minecraft 客户端模组，通过 Krypt04Mcg 0.19.0 的可靠 Data API 和 `KryptSocket`，在两个已验证的玩家之间提供加密、经过身份认证的反向 TCP 端口转发。项目同时支持 Fabric 和 NeoForge，采用 Unlicense。

## 工作方式

假设 Alice 希望把本机 `127.0.0.1:25570` 的连接转发给 Bob 本机正在 `127.0.0.1:8080` 监听的服务：

1. Alice 注册路由并邀请 Bob。
2. Bob 明确接受邀请；授权会绑定 Alice 的已验证玩家身份和不可猜测的路由 UUID。
3. Alice 的客户端开始监听 `127.0.0.1:25570`。
4. 每个进入该端口的 TCP 连接会建立一条到 Bob 的 `KryptSocket`。Bob 验证身份与路由 UUID 后，连接自己的 `127.0.0.1:8080`，随后双向转发字节流。

监听端和目标端都固定使用回环地址，不会意外向局域网或公网暴露服务。每条路由最多同时处理 8 个连接。路由和已接受的授权保存在 `config/k04m-reverse-forward/routes.dat`；尚未处理的邀请只保留两分钟且不会写入磁盘。

## 前置条件

- Minecraft Java 26.3、Java 25；
- Fabric Loader 0.19.5 + Fabric API 0.161.0+26.3，或 NeoForge 26.3.0.16-beta；
- 双方安装对应加载器版本的 Krypt04Mcg 0.19.0 或更高版本，以及本模组；
- 双方在 Krypt04Mcg 中启用 `enableDataApi`，并按 Krypt04Mcg 文档导入/信任对方公钥；
- 服务端 Relay 必须透明转发 `krypt04mcg:data` Custom Payload。

本项目仅以 `compileOnly` 方式引用 `libs/krypt04mcg-0.19.0.jar`。构建产物不会打包 Krypt04Mcg，也不会修改其代码。运行时必须单独安装 Krypt04Mcg；NeoForge 用户需要安装 Krypt04Mcg 的 NeoForge 构建，而不是把这里的 Fabric JAR 直接放入 NeoForge。

## 命令

所有命令都是客户端命令：

```text
/k04mrf register <name> <listenPort> <targetPort>
/k04mrf invite <name> <player>
/k04mrf invitations
/k04mrf accept <invitationId>
/k04mrf deny <invitationId>
/k04mrf list
/k04mrf stop <name>
/k04mrf start <name>
/k04mrf remove <name>
/k04mrf revoke <routeId>
/k04mrf help
```

`invitationId` 和 `routeId` 可以使用 `/k04mrf invitations` 或 `/k04mrf list` 显示的 8 位前缀；若前缀不唯一，命令会拒绝执行。

### 示例

Alice 执行：

```text
/k04mrf register web 25570 8080
/k04mrf invite web Bob
```

Bob 会看到带短邀请 ID 的提示，然后执行（示例 ID）：

```text
/k04mrf accept a1b2c3d4
```

接受确认可靠送达 Alice 后，她的客户端会监听 `127.0.0.1:25570`。访问该地址的 TCP 流量会由 Bob 本机 `127.0.0.1:8080` 的程序处理。

`stop` 只停止 Alice 的本地入口并保留授权；`start` 重新开启。`remove` 删除发起方路由并通知对方撤销授权。Bob 也可以用 `/k04mrf revoke <routeId>` 主动撤销某条入站授权。

## 构建

仓库自带 Gradle Wrapper。需要可访问 Minecraft/Fabric/NeoForge Maven 仓库的网络环境。

```powershell
# Fabric
.\gradlew.bat build

# NeoForge
.\gradlew.bat -p neoforge build
```

产物分别位于：

- `build/libs/k04m-reverse-forward-fabric-1.0.0.jar`
- `neoforge/build/libs/k04m-reverse-forward-neoforge-1.0.0.jar`

## 安全和运行限制

- 底层机密性、身份认证、重试和有序流依赖 Krypt04Mcg；请留意它仍是实验性软件，不应用于敏感或生产级流量。
- 只有受邀者明确接受后才会持久化入站授权。收到 socket 时会再次核对 Krypt04Mcg 给出的已验证发送者与路由 UUID。
- 模组不会开放非回环地址、自动运行文件或启动目标服务。
- `KryptSocket` 输出必须在 Minecraft 客户端线程执行；本模组会把这部分工作调度到客户端线程，而阻塞的 TCP/socket 读取运行在虚拟线程中。
- 断开服务器时所有监听器和活动 Krypt04Mcg 流都会终止；重新连接后，已启用且已接受的路由会恢复监听。
- Krypt04Mcg 的流具有 1 MiB 输出队列和窗口限制。对端或中继过慢时，单条连接可能因背压关闭，这是有界资源设计的一部分。

## 协议概览

控制通道为 `k04m_reverse_forward:control`，使用版本化二进制消息发送邀请、接受、拒绝和撤销。数据通道为 `k04m_reverse_forward:tunnel`。每条新流首先发送带有 `K04M` 标识的 magic、协议版本与 128 位路由 UUID；接收方完成玩家身份和授权检查、成功连接本地目标后返回状态字节 `0`，之后才开始转发应用数据。

协议只在 Krypt04Mcg API 提供的认证和加密信道内使用；它不自行实现加密，也不绕过 Krypt04Mcg 的信任检查。
