# HundredFloorRush

当前版本：1.3.0

Paper/Folia 1.21.11 的“速下百层”垂直竞速小游戏插件。插件负责比赛状态、倒计时、楼层检测、排名和恢复，并默认自动创建可复现的独立虚空赛道；也保留管理员手工标定地图的高级流程。

## 环境

- Java 21
- Paper 1.21.11 或 Folia 1.21.11

## 快速开始

1. 启动一次服务器生成 `config.yml` 和 `messages.yml`。
2. 使用 `/hfr create <名称> [种子] [楼层]` 自动生成地图（省略种子随机生成，省略楼层使用配置值）。`generate` 是同功能别名。
3. 玩家使用 `/hfr join <名称>` 加入；管理员可 `/hfr start <名称>` 强制开始。
4. 管理员可使用 `/hfr gui` 打开竞技场管理 GUI，或 `/hfr gui <名称>` 直接编辑指定竞技场。GUI 中可创建自动地图、设置等待点/起点/终点、记录边界点、添加楼层入口、异步保存、强制开始和二次确认删除。

默认可通过点击名为“速下百层”的村民 NPC 自动加入等待区，也可以继续使用 `/hfr join <名称>`。NPC 入场由 `config.yml` 的 `join-npc` 配置控制；设置 `join-npc.arena` 后点击会直接进入指定竞技场，留空时仅存在一个竞技场才会自动选择。进入等待区后会获得一个带安全标识的粘液球“返回大厅”。道具不可堆叠、不可丢弃、不可移动，并固定在快捷栏最后一个槽位；说明会显示在物品 Lore 中。左键或右键点击即可通过代理返回独立大厅子服，比赛正式开始时自动清除；比赛结束后默认展示 5 秒，再返回配置中的大厅子服；也可以使用 `/hfr leave`。请在 `config.yml` 的 `proxy.lobby-server` 填写代理中的大厅名称，并确保 Velocity 开启 BungeeCord 兼容插件消息通道（BungeeCord 代理可直接使用）。

NPC 默认配置：

```yaml
join-npc:
  enabled: true
  entity-type: VILLAGER
  name: 速下百层
  arena: ""
```

NPC 名称按去除颜色后的纯文本匹配；配置 `arena` 为空时，服务器中必须只有一个竞技场才能自动选择。

手工地图流程：

1. 使用 `/hfr edit <名称>` 创建手工竞技场草稿。
2. 站在等待区执行 `/hfr set waiting`，站在起点执行 `/hfr set start`。
3. 站在赛道边界的一个角落执行 `/hfr pos1`，在对角角落执行 `/hfr pos2`，再执行 `/hfr set bounds`。
4. 按楼层从上到下站在每层金色下落入口中心，执行 `/hfr add-floor`。
5. 站在终点执行 `/hfr set finish`，执行 `/hfr save`。

`edit` 创建的草稿会按玩家 UUID 记录为“当前草稿”，因此上述命令可以省略竞技场名称。为方便控制台或同时编辑多个草稿，仍支持显式写法：

```text
/hfr set waiting <名称>
/hfr set start <名称>
/hfr set bounds <名称>
/hfr add-floor <名称>
/hfr set finish <名称>
/hfr save <名称>
```

`/hfr pos1` 和 `/hfr pos2` 只记录当前玩家的边界选点，不需要额外参数。管理员退出服务器或插件关闭时应清理这些临时选点；未保存的草稿仍保留在内存中，便于管理员继续编辑。

每层入口需要由地图搭建者确保安全可落入；插件只读取入口坐标，不在比赛线程内大规模修改世界。

## LuckPerms

检测到 LuckPerms 时，插件会按 `config.yml` 的 `luckperms` 节点异步创建/补全 `hfr-player` 与 `hfr-admin` 组。普通玩家组包含帮助、加入和离开权限，管理员组继承普通玩家组并包含管理与 GUI 权限；默认组可配置为继承普通玩家组。插件不会擅自把任何玩家提升为管理员，管理员可以按服务器规范执行 `/lp user <玩家> parent add hfr-admin`。

## 地图模板来源

联网检索到的 MIT 许可跑酷参考及兼容性说明见 [MAP_TEMPLATE_REFERENCE.md](MAP_TEMPLATE_REFERENCE.md)。由于参考资源是基岩版 `.mcworld`，插件不直接打包它，而是在 Paper/Folia 1.21.11 中按同类分段跑酷思路生成确定性的竖向地图。
