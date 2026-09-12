package cn.mcxyd.hundredfloor.listener;

import cn.mcxyd.hundredfloor.command.HfrCommand;
import cn.mcxyd.hundredfloor.service.GameService;
import cn.mcxyd.hundredfloor.service.LobbyItemService;
import cn.mcxyd.hundredfloor.scheduler.TaskScheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;

public final class GameListener implements Listener {

    private final GameService game;
    private final LobbyItemService lobbyItems;
    private final TaskScheduler scheduler;
    private final HfrCommand command;

    public GameListener(GameService game, LobbyItemService lobbyItems, TaskScheduler scheduler) {
        this(game, lobbyItems, scheduler, null);
    }

    public GameListener(GameService game, LobbyItemService lobbyItems, TaskScheduler scheduler,
                        HfrCommand command) {
        this.game = game;
        this.lobbyItems = lobbyItems;
        this.scheduler = scheduler;
        this.command = command;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || (event.getFrom().getX() == event.getTo().getX()
                && event.getFrom().getY() == event.getTo().getY()
                && event.getFrom().getZ() == event.getTo().getZ())) {
            return;
        }
        game.onMove(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (!game.isPlaying(event.getPlayer().getUniqueId())) {
            if (lobbyItems.isLobbyItem(event.getItem())) {
                // 旧会话/重载残留道具不能重新触发传送，也不能继续留在玩家背包。
                event.setCancelled(true);
                lobbyItems.removeAll(event.getPlayer());
            }
            return;
        }
        if (!lobbyItems.isLobbyItem(event.getItem())) {
            // 竞速期间禁止使用按钮、门、容器和可交互方块，避免玩家绕过赛道
            // 或触发其它插件状态；返回大厅道具仍走下方专用流程。
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        game.returnToLobby(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (lobbyItems.isLobbyItem(event.getHand() == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand())) {
            if (!game.isPlaying(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
                lobbyItems.removeAll(event.getPlayer());
                return;
            }
            event.setCancelled(true);
            game.returnToLobby(event.getPlayer());
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND
                || game.isPlaying(event.getPlayer().getUniqueId())
                || !game.isJoinNpc(event.getRightClicked())) {
            if (game.isPlaying(event.getPlayer().getUniqueId())) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        scheduleNpcJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        if (game.isPlaying(player.getUniqueId())
                && (lobbyItems.isLobbyItem(player.getInventory().getItemInMainHand())
                || lobbyItems.isLobbyItem(player.getInventory().getItemInOffHand()))) {
            event.setCancelled(true);
            game.returnToLobby(player);
            return;
        }
        if (!game.isPlaying(player.getUniqueId()) && game.isJoinNpc(event.getEntity())) {
            event.setCancelled(true);
            scheduleNpcJoin(player);
            return;
        }
        if (game.isPlaying(player.getUniqueId()) && event.getEntity() instanceof org.bukkit.entity.Player victim
                && game.isPlaying(victim.getUniqueId())) {
            // 竞速成绩不应受到玩家互殴击退或伤害影响。
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (game.isPlaying(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (game.isPlaying(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof org.bukkit.entity.Player player
                && game.isPlaying(player.getUniqueId())
                && isExternalInventory(event.getView())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof org.bukkit.entity.Player player
                && game.isPlaying(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * 漏斗及漏斗矿车不经过玩家背包事件；拦截带安全标记的物品，避免返回
     * 大厅道具被自动搬入容器后再被带出小游戏。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        if (event != null && lobbyItems.isLobbyItem(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** 防止容器拾取意外掉落的受保护道具。 */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        if (event != null && event.getItem() != null
                && lobbyItems.isLobbyItem(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /**
     * 在真正死亡前拦截小游戏玩家的致命伤害；这样普通掉落/实体伤害不会进入死亡界面，
     * 也不需要依赖 Folia 中没有稳定替代路径的 PlayerRespawnEvent。
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent event) {
        game.preventFatalDamage(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (game.isPlaying(event.getPlayer().getUniqueId())
                || (event.getItemDrop() != null && lobbyItems.isLobbyItem(event.getItemDrop().getItemStack()))) {
            // 比赛中不允许丢弃任何物品；这样即使返回道具被第三方插件替换
            // 或客户端构造异常事件，也不会产生可拾取复制物。
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof org.bukkit.entity.Player player)) {
            return;
        }
        boolean playing = game.isPlaying(player.getUniqueId());
        if (!playing) {
            // reload/异常退出后即使会话表已清空，受保护道具也不能被整理、交换或
            // 丢弃；否则它会变成普通可移动物品并跨局残留。
            if (lobbyItems.isLobbyItem(event.getCurrentItem())
                    || lobbyItems.isLobbyItem(event.getCursor())
                    || (event.getHotbarButton() >= 0
                    && lobbyItems.isLobbyItem(player.getInventory().getItem(event.getHotbarButton())))) {
                event.setCancelled(true);
            }
            return;
        }
        if (isExternalInventory(event.getView()) && event.getClickedInventory() != null) {
            // 玩家加入比赛前可能已经打开容器；仅禁止返回道具移动不足以阻止
            // 通过旧容器取放物品或触发第三方插件动作。
            event.setCancelled(true);
            return;
        }
        boolean hotbarItem = event.getHotbarButton() >= 0
                && lobbyItems.isLobbyItem(player.getInventory().getItem(event.getHotbarButton()));
        // 不只检查玩家背包：在极端客户端/第三方容器事件中，道具可能已经位于
        // 光标或外部容器。若提前 return，下一次点击就能把它移走。
        boolean protectedItem = hasLobbyItem(player)
                || lobbyItems.isLobbyItem(event.getCurrentItem())
                || lobbyItems.isLobbyItem(event.getCursor())
                || hotbarItem;
        if (!protectedItem) {
            return;
        }
        // 双击整理、shift-click、数字键交换等动作有时不会把被移动的物品放在
        // currentItem/cursor 中；只要会话内存在返回道具，就阻止所有可能改变
        // 玩家背包布局的批量动作。
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                || event.getAction() == InventoryAction.HOTBAR_SWAP
                || event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY
                // F 键交换副手时，当前物品/光标不一定携带返回道具；
                // 只要背包内存在道具就必须取消，避免它从副手被搬到任意槽位。
                || event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND) {
            event.setCancelled(true);
            return;
        }
        if (event.getClickedInventory() == player.getInventory() && event.getSlot() == 8) {
            event.setCancelled(true);
            return;
        }
        if (lobbyItems.isLobbyItem(event.getCurrentItem())
                || lobbyItems.isLobbyItem(event.getCursor()) || hotbarItem) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof org.bukkit.entity.Player player) {
            boolean playing = game.isPlaying(player.getUniqueId());
            boolean protectedItem = lobbyItems.isLobbyItem(event.getOldCursor())
                    || lobbyItems.isLobbyItem(event.getCursor())
                    || event.getNewItems().values().stream().anyMatch(lobbyItems::isLobbyItem)
                    || (playing && hasLobbyItem(player));
            boolean externalDuringMatch = playing && isExternalInventory(event.getView())
                    && !event.getNewItems().isEmpty();
            if (protectedItem || externalDuringMatch) {
            // 拖拽到玩家背包的目标槽位可能不包含在 newItems 中（尤其是跨容器拖拽），
            // 为保证槽位固定，在等待区持有该道具时取消整个拖拽操作。
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if ((game.isPlaying(event.getPlayer().getUniqueId())
                && (lobbyItems.isLobbyItem(event.getMainHandItem())
                || lobbyItems.isLobbyItem(event.getOffHandItem())))
                || lobbyItems.isLobbyItem(event.getMainHandItem())
                || lobbyItems.isLobbyItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        game.onDeath(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        game.onQuit(event);
        if (command != null) {
            command.clearPlayerState(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        game.onJoin(event.getPlayer());
    }

    private void scheduleNpcJoin(org.bukkit.entity.Player player) {
        java.util.UUID playerId = player.getUniqueId();
        // Paper 主线程和 Folia 玩家实体线程都通过统一调度器进入，避免异步线程直接访问 Player。
        scheduler.runPlayer(playerId, game::joinFromNpc);
    }

    private boolean hasLobbyItem(org.bukkit.entity.Player player) {
        // getStorageContents() 不包含盔甲槽；异常客户端/容器实现可能把带标识
        // 的物品放入盔甲槽。统一扫描完整 PlayerInventory，避免随后通过整理、
        // 数字键或容器拖拽把道具复制/移出固定槽位。
        return java.util.Arrays.stream(player.getInventory().getContents())
                .anyMatch(lobbyItems::isLobbyItem)
                || java.util.Arrays.stream(player.getInventory().getArmorContents())
                .anyMatch(lobbyItems::isLobbyItem)
                || lobbyItems.isLobbyItem(player.getInventory().getItemInOffHand());
    }

    private static boolean isExternalInventory(InventoryView view) {
        if (view == null || view.getTopInventory() == null) {
            return false;
        }
        InventoryType type = view.getTopInventory().getType();
        // 玩家自己的生存/创造背包不是地图容器；箱子、熔炉以及第三方 GUI
        // 均属于外部界面，比赛期间禁止继续操作。
        return type != InventoryType.CRAFTING && type != InventoryType.CREATIVE;
    }
}
