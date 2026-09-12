package cn.mcxyd.hundredfloor;

import cn.mcxyd.hundredfloor.command.HfrCommand;
import cn.mcxyd.hundredfloor.config.ConfigurationManager;
import cn.mcxyd.hundredfloor.listener.GameListener;
import cn.mcxyd.hundredfloor.scheduler.PaperFoliaTaskScheduler;
import cn.mcxyd.hundredfloor.scheduler.ServerPlatform;
import cn.mcxyd.hundredfloor.scheduler.TaskHandle;
import cn.mcxyd.hundredfloor.scheduler.TaskScheduler;
import cn.mcxyd.hundredfloor.service.ArenaRepository;
import cn.mcxyd.hundredfloor.service.ArenaGenerationService;
import cn.mcxyd.hundredfloor.service.GameService;
import cn.mcxyd.hundredfloor.service.LobbyItemService;
import cn.mcxyd.hundredfloor.service.MessageService;
import cn.mcxyd.hundredfloor.service.ProxyService;
import cn.mcxyd.hundredfloor.service.RecoveryRepository;
import cn.mcxyd.hundredfloor.service.RecordRepository;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

public final class HundredFloorRushPlugin extends JavaPlugin {

    private ConfigurationManager configuration;
    private TaskScheduler scheduler;
    private GameService game;
    private ProxyService proxy;
    private HfrCommand command;
    private ArenaRepository arenas;
    private ArenaGenerationService generation;
    private TaskHandle gameTick;

    @Override
    public void onEnable() {
        boolean folia = ServerPlatform.isFolia();
        configuration = new ConfigurationManager(this);
        try {
            configuration.initialize();
        } catch (RuntimeException exception) {
            getLogger().severe("配置加载失败，插件将安全关闭：" + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        arenas = new ArenaRepository(this);
        RecordRepository records = new RecordRepository(this);
        RecoveryRepository recoveries = new RecoveryRepository(this);
        try {
            arenas.load();
            records.load();
            recoveries.load();
        } catch (Exception exception) {
            getLogger().severe("无法载入持久化数据，插件将安全关闭：" + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        scheduler = new PaperFoliaTaskScheduler(this, folia);
        generation = new ArenaGenerationService(this, arenas, scheduler);
        generation.loadStoredWorlds();
        MessageService messages = new MessageService(configuration.messages());
        proxy = new ProxyService(this, configuration);
        proxy.register();
        LobbyItemService lobbyItems = new LobbyItemService(this, messages);
        game = new GameService(configuration, arenas, records, recoveries, messages, scheduler, proxy, lobbyItems);

        command = new HfrCommand(configuration, arenas, game, messages, generation, scheduler);
        PluginCommand pluginCommand = Objects.requireNonNull(getCommand("hfr"), "plugin.yml 缺少 hfr 指令");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        getServer().getPluginManager().registerEvents(new GameListener(game, lobbyItems, scheduler, command), this);

        // /reload 不会重新触发 PlayerJoinEvent；主动把当前在线玩家身上的旧
        // 返回大厅道具安排到各自实体上下文清理，避免旧 PDC 物品失去保护。
        for (Player online : Bukkit.getOnlinePlayers()) {
            scheduler.runPlayer(online.getUniqueId(), target -> {
                if (!game.isPlaying(target.getUniqueId())) {
                    lobbyItems.removeAll(target);
                }
            });
        }

        // 统一在全局上下文推进所有比赛状态，业务线程不直接操作 BukkitScheduler。
        gameTick = scheduler.runGlobalTimer(game::tick, 1, 1);
        if (gameTick == null || gameTick.cancelled()) {
            // 没有全局状态循环时，倒计时、超时和结果清理都不会推进；继续
            // 保持“已启用”反而会留下玩家无法结束的幽灵比赛。
            getLogger().severe("无法启动比赛状态循环，插件将安全关闭");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getLogger().info("HundredFloorRush 已启用，目标 Paper/Folia 1.21.11，Folia=" + folia);
    }

    @Override
    public void onDisable() {
        // 关闭流程必须逐项隔离：第三方调度器、世界生成器或代理实现即使
        // 抛出运行时异常，也不能阻止后续任务、会话和通道清理。
        TaskHandle tick = gameTick;
        gameTick = null;
        closeStep("比赛定时任务", tick == null ? null : tick::cancel);

        HfrCommand commandRef = command;
        command = null;
        closeStep("指令临时状态", commandRef == null ? null : commandRef::clearTransientState);

        // 先停止地图生成回调，再让游戏服务进入关闭状态；否则生成任务可能在
        // shutdown 清理会话后继续写回旧竞技场。最后关闭代理和统一调度器。
        ArenaGenerationService generationRef = generation;
        generation = null;
        closeStep("地图生成任务", generationRef == null ? null : generationRef::close);

        GameService gameRef = game;
        game = null;
        closeStep("游戏会话与恢复数据", gameRef == null ? null : gameRef::shutdown);

        ArenaRepository arenasRef = arenas;
        arenas = null;
        closeStep("竞技场草稿", arenasRef == null ? null : arenasRef::clearDrafts);

        ProxyService proxyRef = proxy;
        proxy = null;
        closeStep("代理插件消息通道", proxyRef == null ? null : proxyRef::close);

        TaskScheduler schedulerRef = scheduler;
        scheduler = null;
        closeStep("统一调度器", schedulerRef == null ? null : schedulerRef::close);

        // 配置对象不持有外部资源；清空字段可避免插件对象在异常回调期间
        // 继续保留整套旧实例引用（正在执行的任务仍由其自身生命周期管理）。
        configuration = null;
    }

    /**
     * Executes one shutdown action without allowing it to abort the remaining
     * cleanup steps.  Shutdown runs at a plugin boundary, so even an
     * unexpected Error is logged and isolated to protect the server's other
     * plugins and the resources handled below.
     */
    private void closeStep(String name, Runnable action) {
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (Throwable exception) {
            getLogger().log(Level.SEVERE, "插件关闭阶段失败（" + name + "），已继续清理其他资源", exception);
        }
    }
}
