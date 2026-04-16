package com.example.fakeitemguard;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FakeItemGuard
 *
 * 防止玩家利用客户端假物品(服务端无、客户端有)在鞘翅飞行中触发 mod 的
 * 客户端预测加速度,进而达到无限飞行的效果。
 *
 * 原理:假物品在服务端不存在,所以玩家"用假物品加速飞行"时服务端主手一定是 AIR。
 *      当检测到玩家在滑翔 + 主手为空时,强制调用 updateInventory() 向客户端重发
 *      完整背包快照,抹掉客户端那份幻影物品,客户端 mod 的预测加速代码也就跑不起来了。
 *
 * 三层防护:
 *   1. 起飞瞬间(EntityToggleGlideEvent)无条件 resync
 *   2. 每 5 tick 扫描一次,连续命中 2 次才 resync(去除切物品瞬间的误伤)
 *   3. 玩家退出时清理计数表
 */
public class FakeItemGuard extends JavaPlugin implements Listener {

    // 每个飞行玩家连续命中"主手为空"的次数
    private final ConcurrentHashMap<UUID, Integer> airHitCounter = new ConcurrentHashMap<>();

    // 连续命中多少次才执行 resync。SCAN_INTERVAL * HIT_THRESHOLD 即最长容忍时间。
    // 默认 5 tick * 2 = 10 tick = 0.5s
    private int hitThreshold;

    // 扫描间隔(tick)
    private long scanInterval;

    // 是否记录 resync 日志(排查用)
    private boolean logResync;

    private BukkitTask scanTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        getServer().getPluginManager().registerEvents(this, this);
        scanTask = Bukkit.getScheduler().runTaskTimer(this, this::scan, scanInterval, scanInterval);

        getLogger().info("FakeItemGuard enabled. scanInterval=" + scanInterval
                + " tick, hitThreshold=" + hitThreshold
                + ", logResync=" + logResync);
    }

    @Override
    public void onDisable() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        airHitCounter.clear();
        getLogger().info("FakeItemGuard disabled.");
    }

    private void reloadSettings() {
        reloadConfig();
        this.hitThreshold = Math.max(1, getConfig().getInt("hit-threshold", 2));
        this.scanInterval = Math.max(1L, getConfig().getLong("scan-interval-ticks", 5L));
        this.logResync = getConfig().getBoolean("log-resync", false);
    }

    /**
     * 起飞瞬间无条件 resync 一次,最便宜的预防。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();

        if (!event.isGliding()) {
            // 落地/停止滑翔时清计数,避免跨次飞行累加
            airHitCounter.remove(p.getUniqueId());
            return;
        }

        // 开始滑翔 → 立刻重发一次背包
        p.updateInventory();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        airHitCounter.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 定时扫描:只处理"正在滑翔 + 主手为空"的玩家,连续命中到阈值才发包。
     */
    private void scan() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();

            if (!p.isGliding()) {
                airHitCounter.remove(id);
                continue;
            }

            Material main = p.getInventory().getItemInMainHand().getType();
            if (main != Material.AIR) {
                airHitCounter.remove(id);
                continue;
            }

            int count = airHitCounter.merge(id, 1, Integer::sum);
            if (count >= hitThreshold) {
                p.updateInventory();
                airHitCounter.remove(id);

                if (logResync) {
                    getLogger().info("Resynced inventory for " + p.getName()
                            + " (gliding with empty main hand)");
                }
            }
        }
    }
}
