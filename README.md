# FakeItemGuard

一个针对 **Arclight 1.20.1** 的轻量 Bukkit 插件,用于防止玩家利用客户端假物品(服务端不存在但客户端显示)在鞘翅飞行时触发 mod 的客户端预测加速度,从而实现无限飞行的漏洞。

## 适用场景

- 服务端 Forge mod 的武器/道具右键技能由客户端预测实现
- 玩家通过展示类插件 + Arclight 的桥接缺陷卡出"假物品"(服务端侧主手为 AIR,客户端看得到)
- `/data get entity <玩家> SelectedItem` 返回"没有匹配元素"但玩家依然能触发技能

## 工作原理

插件持续监控处于滑翔状态的玩家:
1. **起飞瞬间**:`EntityToggleGlideEvent` 触发时无条件调用 `updateInventory()` 重发完整背包,抹掉客户端任何幻影物品
2. **飞行中**:每 5 tick 扫描一次,若玩家在滑翔且主手为 `AIR` 并连续命中 2 次(约 0.5 秒),再次 `updateInventory()`
3. **清理**:玩家退出或停止滑翔时清除计数

`updateInventory()` 本质是发一个 `ContainerSetContentPacket`,强制客户端接受服务端的真实背包状态,客户端 mod 的"手上有战戟"判断就会 fail,加速代码不跑。

## 编译

需要 JDK 17。

```bash
git clone <your-fork-url>
cd FakeItemGuard
./gradlew build
```

产物位置:`build/libs/FakeItemGuard-1.0.0.jar`

Windows 下用 `gradlew.bat build`,如果仓库里没有 Gradle wrapper,先跑一次 `gradle wrapper --gradle-version 8.5` 生成。

## 安装

把 `FakeItemGuard-1.0.0.jar` 丢进 `plugins/`,重启服务器。第一次启动会生成 `plugins/FakeItemGuard/config.yml`。

## 配置

```yaml
# 扫描间隔(tick)。20 tick = 1 秒。
scan-interval-ticks: 5

# 连续命中多少次"飞行中主手为空"才 resync。
# 最长容忍时间 = scan-interval-ticks * hit-threshold
# 默认 5 * 2 = 0.5 秒
hit-threshold: 2

# 排查阶段建议开启,稳定后关闭
log-resync: false
```

## 性能

- 每次扫描:遍历在线玩家 + 两次字段读,百人服微秒级
- `updateInventory()` 只在真正命中时发,一次约 1-2 KB
- 正常玩家几乎永远不触发第二层检测

## 调参建议

- **误伤较多**(正常玩家背包偶尔被刷新):调大 `hit-threshold` 到 3 或 4
- **压制不够快**:调小 `scan-interval-ticks` 到 3,或 `hit-threshold` 设为 1
- **排查阶段**:开 `log-resync`,跑几天看哪些玩家频繁触发

## 局限

- 只处理**滑翔中主手为空**这一具体模式。如果漏洞以其他形式出现(副手、地面使用等),需要扩展检测
- 这是兜底方案。根治做法是 Mixin hook mod 的加速度代码,强制校验服务端主手,或把 CD 从 NBT 改为 `player.getCooldowns()`

## License

MIT
