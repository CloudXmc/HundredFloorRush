package cn.mcxyd.hundredfloor.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 管理返回大厅道具的创建、识别和清理，避免依赖可伪造的物品名称。 */
public final class LobbyItemService {

    private final JavaPlugin plugin;
    private final MessageService messages;
    private final NamespacedKey marker;

    public LobbyItemService(JavaPlugin plugin, MessageService messages) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.messages = messages;
        this.marker = new NamespacedKey(plugin, "return-lobby");
    }

    public boolean give(Player player) {
        if (player == null) {
            return false;
        }
        try {
            PlayerInventory inventory = player.getInventory();
            // 先清理旧的受保护道具，避免重连、重复点击或第三方插件发放
            // 导致多个标记物品共存；该方法只在玩家实体上下文调用。
            removeAll(player);
            int lastHotbarSlot = 8;
            ItemStack existing = inventory.getItem(lastHotbarSlot);
            // 如果第三方背包实现刚才未能清掉第 9 格的旧标记物品，不能把
            // 它 clone 到空槽后再放入新物品，否则会产生可复制的返回道具。
            if (isLobbyItem(existing)) {
                try {
                    inventory.setItem(lastHotbarSlot, null);
                    existing = null;
                } catch (RuntimeException exception) {
                    plugin.getLogger().log(java.util.logging.Level.WARNING,
                            "无法清理第 9 格旧返回大厅道具，已拒绝重复发放", exception);
                    return false;
                }
            }
            // 其它槽位仍残留标记时也拒绝发放；宁可让玩家稍后重试，
            // 也不能在异常背包实现中制造第二个受保护物品。
            if (hasAnyLobbyItem(player, inventory)) {
                plugin.getLogger().warning("玩家背包仍有未清理的返回大厅道具，已拒绝重复发放");
                return false;
            }
            // 返回道具必须固定在快捷栏最后一格。为避免离场时无法可靠地把
            // 原物品放回原槽位，也避免第三方背包把 firstEmpty() 指向盔甲/副手，
            // 第 9 格已有普通物品时直接拒绝入场，不覆盖也不搬动物品。
            if (existing != null && !existing.getType().isAir()) {
                messages.send(player, "lobby-item-inventory-full", Map.of());
                return false;
            }
            // 先完整构造物品，再开始移动玩家原物品；若核心拒绝 ItemMeta，
            // 不会留下半成品或覆盖快捷栏。
            ItemStack item = createItem();
            try {
                inventory.setItem(lastHotbarSlot, item);
                return true;
            } catch (RuntimeException exception) {
                try {
                    inventory.setItem(lastHotbarSlot, existing == null ? null : existing.clone());
                } catch (RuntimeException ignored) {
                    // 无法恢复时仍返回失败；不会继续登记本局。
                }
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "无法放置返回大厅道具，已拒绝进入等待区", exception);
                return false;
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "构造返回大厅道具失败，已拒绝进入等待区", exception);
            return false;
        }
    }

    /**
     * Checks whether the protected ninth hotbar slot can be populated without
     * overwriting a player's item.  This is a read-only check and must be called
     * from the player's entity context.
     */
    public boolean canGive(Player player) {
        if (player == null) {
            return false;
        }
        try {
            PlayerInventory inventory = player.getInventory();
            if (isLobbyItem(inventory.getItem(8)) || hasAnyLobbyItem(player, inventory)) {
                return true;
            }
            if (inventory.getItem(8) == null || inventory.getItem(8).getType().isAir()) {
                return true;
            }
            // give() 不会搬动第 9 格原物品，因此只有该槽为空时才能保证事务性。
            return false;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "读取玩家背包失败，已拒绝进入等待区", exception);
            return false;
        }
    }

    public boolean isLobbyItem(ItemStack item) {
        if (item == null || item.getType() != Material.SLIME_BALL || !item.hasItemMeta()) {
            return false;
        }
        try {
            Byte value = item.getItemMeta().getPersistentDataContainer().get(marker, PersistentDataType.BYTE);
            return value != null && value == (byte) 1;
        } catch (RuntimeException ignored) {
            // 损坏或由其他实现提供的 ItemMeta 不能让背包事件线程中断。
            return false;
        }
    }

    public void removeAll(Player player) {
        if (player == null) {
            return;
        }
        try {
            PlayerInventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                try {
                    ItemStack item = inventory.getItem(slot);
                    if (isLobbyItem(item)) {
                        inventory.setItem(slot, null);
                    }
                } catch (RuntimeException exception) {
                    // 一个损坏槽位不应阻止清理其它槽位；记录后继续，
                    // 以免重复加入时留下可触发道具。
                    plugin.getLogger().log(java.util.logging.Level.FINE,
                            "清理返回大厅道具时跳过异常槽位", exception);
                }
            }
            try {
                ItemStack offHand = inventory.getItemInOffHand();
                if (isLobbyItem(offHand)) {
                    inventory.setItemInOffHand(null);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.FINE,
                        "清理副手返回大厅道具时失败", exception);
            }
            // 光标物品不属于 PlayerInventory；若玩家在加入前已把受保护道具
            // 留在光标，单纯扫描背包会让它绕过固定槽位并在跨服时复制/残留。
            try {
                ItemStack cursor = player.getItemOnCursor();
                if (isLobbyItem(cursor)) {
                    player.setItemOnCursor(null);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.FINE,
                        "清理光标返回大厅道具时失败", exception);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "读取玩家背包失败，无法清理返回大厅道具", exception);
        }
    }

    private boolean hasAnyLobbyItem(Player player, PlayerInventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (isLobbyItem(item)) {
                return true;
            }
        }
        if (isLobbyItem(inventory.getItemInOffHand())) {
            return true;
        }
        try {
            return player != null && isLobbyItem(player.getItemOnCursor());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private ItemStack createItem() {
        ItemStack item = new ItemStack(Material.SLIME_BALL);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("SLIME_BALL 没有可用 ItemMeta");
        }
        meta.displayName(noItalic(messages.renderRaw("lobby-item-name", Map.of())));
        List<Component> lore = new ArrayList<>();
        for (String line : messages.rawList("lobby-item-lore")) {
            lore.add(noItalic(messages.parseRaw(line)));
        }
        meta.lore(lore);
        meta.setMaxStackSize(1);
        meta.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        item.setAmount(1);
        return item;
    }

    /** Item lore/name 默认可能继承斜体；逐层显式关闭，避免客户端样式漂移。 */
    private static Component noItalic(Component component) {
        if (component == null) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        List<Component> children = component.children();
        Component result = component.decoration(TextDecoration.ITALIC, false);
        if (!children.isEmpty()) {
            result = result.children(children.stream().map(LobbyItemService::noItalic).toList());
        }
        return result;
    }
}
