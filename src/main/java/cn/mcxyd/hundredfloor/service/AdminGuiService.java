package cn.mcxyd.hundredfloor.service;

import cn.mcxyd.hundredfloor.config.AdminGuiConfig;
import cn.mcxyd.hundredfloor.config.ConfigurationManager;
import cn.mcxyd.hundredfloor.game.ArenaValidator;
import cn.mcxyd.hundredfloor.game.ValidationResult;
import cn.mcxyd.hundredfloor.game.model.ArenaBounds;
import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;
import cn.mcxyd.hundredfloor.scheduler.TaskScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理员竞技场 GUI。会话只保存 UUID 和不可变快照，不长期持有 Player、World 或 Inventory。
 * 所有背包读写、坐标读取和打开/关闭界面都回到玩家实体上下文。
 */
public final class AdminGuiService {

    public static final String PERMISSION = "hundredfloorrush.gui.admin";

    private final JavaPlugin plugin;
    private final ConfigurationManager configuration;
    private final ArenaRepository arenas;
    private final ArenaGenerationService generation;
    private final GameService game;
    private final MessageService messages;
    private final TaskScheduler scheduler;
    private final LuckPermsIntegration luckPerms;
    private final AdminGuiConfig guiConfig;
    private final ArenaValidator validator = new ArenaValidator();
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, InputState> inputStates = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AdminGuiService(JavaPlugin plugin, ConfigurationManager configuration, ArenaRepository arenas,
                           ArenaGenerationService generation, GameService game, MessageService messages,
                           TaskScheduler scheduler, LuckPermsIntegration luckPerms) {
        this.plugin = plugin;
        this.configuration = configuration;
        this.arenas = arenas;
        this.generation = generation;
        this.game = game;
        this.messages = messages;
        this.scheduler = scheduler;
        this.luckPerms = luckPerms;
        this.guiConfig = new AdminGuiConfig(plugin);
        this.guiConfig.initialize();
    }

    public void open(Player player, String arenaName) {
        if (player == null || closed.get()) {
            return;
        }
        UUID id = player.getUniqueId();
        scheduler.runPlayer(id, target -> {
            if (!target.hasPermission(PERMISSION)) {
                messages.send(target, "no-permission", Map.of());
                return;
            }
            if (arenaName == null || arenaName.isBlank()) {
                openMainNow(target);
            } else {
                openEditorNow(target, arenaName.trim());
            }
        });
    }

    public boolean reload() {
        return guiConfig.reload();
    }

    public void clearPlayer(UUID playerId) {
        if (playerId != null) {
            sessions.remove(playerId);
            inputStates.remove(playerId);
        }
    }

    public void clearAll() {
        sessions.clear();
        inputStates.clear();
        closed.set(true);
    }

    public void handleClick(InventoryClickEvent event) {
        if (event == null || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission(PERMISSION) || closed.get()) {
            scheduler.runPlayer(player.getUniqueId(), Player::closeInventory);
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !session.token().equals(holder.token()) || !session.screen().equals(holder.screen())
                || !java.util.Objects.equals(session.arenaName(), holder.arenaName())) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getView().getTopInventory().getSize()) {
            return;
        }
        String action = actionAt(holder.screen(), rawSlot, guiConfig.current());
        if (action == null || action.equals("none")) {
            return;
        }
        UUID id = player.getUniqueId();
        if (!session.actionBusy().compareAndSet(false, true)) {
            return;
        }
        scheduler.runPlayer(id, target -> executeAction(target, session, action, rawSlot));
    }

    public void handleDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (event != null && event.getView().getTopInventory().getHolder() instanceof GuiHolder) {
            event.setCancelled(true);
        }
    }

    public void handleClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event == null || !(event.getPlayer() instanceof Player player)
                || !(event.getView().getTopInventory().getHolder() instanceof GuiHolder holder)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session != null && session.token().equals(holder.token())) {
            sessions.remove(player.getUniqueId(), session);
        }
    }

    private void executeAction(Player player, Session session, String action, int slot) {
        if (player == null || !player.isOnline() || closed.get()) {
            session.actionBusy().set(false);
            return;
        }
        try {
            switch (action) {
                case "open-arena" -> {
                    String arena = arenaAt(session, slot);
                    if (arena != null) {
                        openEditorNow(player, arena);
                    }
                }
                case "create-map" -> beginCreate(player);
                case "reload" -> reloadFromGui(player);
                case "close" -> {
                    clearPlayer(player.getUniqueId());
                    player.closeInventory();
                }
                case "set-waiting", "set-start", "set-finish", "set-pos1", "set-pos2", "set-bounds",
                        "add-floor", "save", "start", "confirm-delete", "delete", "cancel-delete", "back" ->
                        editAction(player, session, action);
                default -> plugin.getLogger().warning("忽略未注册的管理员 GUI action：" + action);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "管理员 GUI 操作失败", exception);
            messages.send(player, "operation-failed-generic", Map.of());
        } finally {
            session.actionBusy().set(false);
        }
    }

    private void editAction(Player player, Session session, String action) {
        String arena = session.arenaName();
        if (arena == null || arena.isBlank()) {
            openMainNow(player);
            return;
        }
        switch (action) {
            case "back" -> openMainNow(player);
            case "cancel-delete" -> openEditorNow(player, arena);
            case "confirm-delete" -> openConfirmNow(player, arena);
            case "delete" -> delete(player, arena);
            case "start" -> {
                scheduler.runGlobal(() -> game.start(arena));
                messages.send(player, "game-start-requested", Map.of("arena", arena));
            }
            case "save" -> save(player, arena);
            case "add-floor" -> {
                ArenaRepository.ArenaDraft draft = ensureDraft(arena);
                if (draft.floorCount() >= 100) {
                    messages.send(player, "floor-limit", Map.of());
                    return;
                }
                draft.addFloor(point(player));
                messages.send(player, "floor-added", Map.of("floor", draft.floorCount()));
                openEditorNow(player, arena);
            }
            case "set-pos1" -> {
                session.pos1(point(player));
                messages.send(player, "position-set", Map.of("type", "pos1"));
            }
            case "set-pos2" -> {
                session.pos2(point(player));
                messages.send(player, "position-set", Map.of("type", "pos2"));
            }
            case "set-bounds" -> {
                ArenaPoint first = session.pos1();
                ArenaPoint second = session.pos2();
                if (first == null || second == null) {
                    messages.send(player, "selection-required", Map.of("position", "pos1 和 pos2"));
                    return;
                }
                if (!first.world().equals(second.world())) {
                    messages.send(player, "bounds-different-world", Map.of());
                    return;
                }
                ensureDraft(arena).bounds(new ArenaBounds(first.world(), Math.min(first.x(), second.x()),
                        Math.min(first.y(), second.y()), Math.min(first.z(), second.z()),
                        Math.max(first.x(), second.x()), Math.max(first.y(), second.y()),
                        Math.max(first.z(), second.z())));
                messages.send(player, "position-set", Map.of("type", "bounds"));
                openEditorNow(player, arena);
            }
            case "set-waiting", "set-start", "set-finish" -> {
                ArenaRepository.ArenaDraft draft = ensureDraft(arena);
                ArenaPoint current = point(player);
                if (action.equals("set-waiting")) {
                    draft.waiting(current);
                } else if (action.equals("set-start")) {
                    draft.start(current);
                } else {
                    draft.finish(current);
                }
                messages.send(player, "position-set", Map.of("type", action.substring(4)));
                openEditorNow(player, arena);
            }
            default -> {
            }
        }
    }

    private void beginCreate(Player player) {
        sessions.remove(player.getUniqueId());
        inputStates.put(player.getUniqueId(), new InputState(InputStage.NAME, null, 0L));
        player.closeInventory();
        messages.send(player, "gui-input-name", Map.of());
    }

    public void handleChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        if (event == null) {
            return;
        }
        UUID id = event.getPlayer().getUniqueId();
        InputState state = inputStates.get(id);
        if (state == null || closed.get()) {
            return;
        }
        event.setCancelled(true);
        String value = event.getMessage() == null ? "" : event.getMessage().trim();
        scheduler.runPlayer(id, player -> processInput(player, id, state, value));
    }

    private void processInput(Player player, UUID id, InputState state, String value) {
        if (player == null || !player.isOnline()) {
            inputStates.remove(id);
            return;
        }
        if (value.equalsIgnoreCase("cancel")) {
            inputStates.remove(id);
            messages.send(player, "gui-input-cancelled", Map.of());
            openMainNow(player);
            return;
        }
        switch (state.stage()) {
            case NAME -> {
                if (value.isBlank() || value.length() > 64 || value.indexOf('.') >= 0
                        || value.chars().anyMatch(Character::isISOControl)) {
                    messages.send(player, "arena-name-invalid", Map.of());
                    return;
                }
                inputStates.put(id, new InputState(InputStage.SEED, value, 0L));
                messages.send(player, "gui-input-seed", Map.of());
            }
            case SEED -> {
                long seed;
                try {
                    seed = value.isBlank() || value.equalsIgnoreCase("random")
                            ? java.util.concurrent.ThreadLocalRandom.current().nextLong()
                            : Long.parseLong(value);
                } catch (NumberFormatException exception) {
                    messages.send(player, "invalid-seed", Map.of());
                    return;
                }
                inputStates.put(id, new InputState(InputStage.FLOORS, state.arenaName(), seed));
                messages.send(player, "gui-input-floors", Map.of());
            }
            case FLOORS -> {
                int floors;
                try {
                    floors = Integer.parseInt(value);
                } catch (NumberFormatException exception) {
                    messages.send(player, "invalid-floor-count", Map.of());
                    return;
                }
                if (floors < 1 || floors > 100) {
                    messages.send(player, "floor-count-range", Map.of());
                    return;
                }
                inputStates.remove(id);
                String arena = state.arenaName();
                long seed = state.seed();
                messages.send(player, "arena-generation-started", Map.of("arena", arena,
                        "floors", floors, "seed", seed));
                generation.generate(arena, floors, seed, result -> scheduler.runPlayer(id, target -> {
                    if (result.success()) {
                        messages.send(target, "arena-generated", Map.of("arena", arena,
                                "world", result.definition().waitingSpawn().world(), "seed", result.seed()));
                        openEditorNow(target, arena);
                    } else {
                        messages.send(target, "arena-generation-failed", Map.of("reason", result.reason()));
                    }
                }));
            }
        }
    }

    private void reloadFromGui(Player player) {
        UUID id = player.getUniqueId();
        sessions.remove(id);
        player.closeInventory();
        scheduler.runAsync(() -> {
            var result = configuration.reload();
            if (result.success()) {
                game.reloadMessages();
                boolean guiOk = guiConfig.reload();
                if (luckPerms != null) {
                    scheduler.runGlobal(() -> luckPerms.reload(configuration.luckPerms()));
                }
                scheduler.runPlayer(id, target -> {
                    messages.send(target, "reload-success", Map.of());
                    if (!guiOk) {
                        messages.send(target, "gui-reload-failed", Map.of());
                    }
                    openMainNow(target);
                });
            } else {
                scheduler.runPlayer(id, target -> messages.send(target, "reload-failed",
                        Map.of("reason", result.reason())));
            }
        });
    }

    private void save(Player player, String arena) {
        ArenaRepository.ArenaDraft draft = ensureDraft(arena);
        ArenaDefinition definition;
        try {
            definition = draft.build();
        } catch (IllegalArgumentException exception) {
            messages.send(player, "draft-incomplete", Map.of());
            return;
        }
        ValidationResult validation = validator.validate(definition, definition.floorGates().size());
        if (!validation.valid()) {
            messages.send(player, "arena-invalid", Map.of("reason", validation.reason()));
            return;
        }
        player.closeInventory();
        UUID id = player.getUniqueId();
        sessions.remove(id);
        ArenaDefinition snapshot = definition;
        scheduler.runAsync(() -> {
            try {
                arenas.saveDraftSnapshot(arena, snapshot);
                scheduler.runPlayer(id, target -> {
                    messages.send(target, "arena-saved", Map.of("arena", arena));
                    openMainNow(target);
                });
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "管理员 GUI 保存竞技场失败", exception);
                scheduler.runPlayer(id, target -> messages.send(target, "operation-failed-generic", Map.of()));
            }
        });
    }

    private void delete(Player player, String arena) {
        if (game.view(arena).isPresent()) {
            messages.send(player, "arena-delete-running", Map.of("arena", arena));
            return;
        }
        player.closeInventory();
        UUID id = player.getUniqueId();
        sessions.remove(id);
        scheduler.runAsync(() -> {
            try {
                generation.cancel(arena);
                ArenaDefinition expected = arenas.find(arena).orElse(null);
                if (expected == null || !arenas.deleteIfUnchanged(arena, expected)) {
                    scheduler.runPlayer(id, target -> messages.send(target, "arena-missing", Map.of("arena", arena)));
                    return;
                }
                scheduler.runPlayer(id, target -> {
                    messages.send(target, "arena-deleted", Map.of("arena", arena));
                    openMainNow(target);
                });
            } catch (IOException | RuntimeException exception) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "管理员 GUI 删除竞技场失败", exception);
                scheduler.runPlayer(id, target -> messages.send(target, "operation-failed-generic", Map.of()));
            }
        });
    }

    private ArenaRepository.ArenaDraft ensureDraft(String arena) {
        return arenas.draft(arena).orElseGet(() -> arenas.createDraft(arena));
    }

    private void openMainNow(Player player) {
        AdminGuiConfig.Snapshot config = guiConfig.current();
        List<String> names = arenas.all().stream().map(ArenaDefinition::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        Session session = new Session(player.getUniqueId(), UUID.randomUUID(), Screen.MAIN, null, names);
        sessions.put(player.getUniqueId(), session);
        Inventory inventory = createInventory(player, Screen.MAIN, null, config.layout(), config.title(), session, Map.of());
        fillArenaEntries(inventory, config.layout(), names, config);
        player.openInventory(inventory);
    }

    private void openEditorNow(Player player, String arenaName) {
        if (arenas.find(arenaName).isEmpty() && arenas.draft(arenaName).isEmpty()) {
            messages.send(player, "arena-missing", Map.of("arena", arenaName));
            openMainNow(player);
            return;
        }
        ensureDraft(arenaName);
        AdminGuiConfig.Snapshot config = guiConfig.current();
        Session session = new Session(player.getUniqueId(), UUID.randomUUID(), Screen.EDITOR, arenaName, List.of());
        sessions.put(player.getUniqueId(), session);
        Map<String, String> placeholders = Map.of("arena", arenaName,
                "arena_status", status(arenaName));
        Inventory inventory = createInventory(player, Screen.EDITOR, arenaName, config.editorLayout(),
                config.editorTitle(), session, placeholders);
        player.openInventory(inventory);
    }

    private void openConfirmNow(Player player, String arenaName) {
        AdminGuiConfig.Snapshot config = guiConfig.current();
        Session session = new Session(player.getUniqueId(), UUID.randomUUID(), Screen.CONFIRM, arenaName, List.of());
        sessions.put(player.getUniqueId(), session);
        Inventory inventory = createInventory(player, Screen.CONFIRM, arenaName, config.confirmLayout(),
                config.confirmTitle(), session, Map.of("arena", arenaName));
        player.openInventory(inventory);
    }

    private Inventory createInventory(Player player, Screen screen, String arena, List<String> layout,
                                      String title, Session session, Map<String, String> placeholders) {
        GuiHolder holder = new GuiHolder(player.getUniqueId(), session.token(), screen, arena);
        Inventory inventory = Bukkit.createInventory(holder, layout.size() * 9,
                render(title, placeholders));
        holder.inventory(inventory);
        for (int row = 0; row < layout.size(); row++) {
            String line = layout.get(row);
            for (int col = 0; col < 9; col++) {
                char key = line.charAt(col);
                AdminGuiConfig.Icon icon = guiConfig.current().icons().get(key);
                if (icon == null || icon.material() == Material.AIR) {
                    continue;
                }
                inventory.setItem(row * 9 + col, item(icon, placeholders));
            }
        }
        return inventory;
    }

    private void fillArenaEntries(Inventory inventory, List<String> layout, List<String> names,
                                  AdminGuiConfig.Snapshot config) {
        int index = 0;
        AdminGuiConfig.Icon icon = config.icons().get('D');
        if (icon == null) {
            return;
        }
        for (int row = 0; row < layout.size(); row++) {
            for (int col = 0; col < 9; col++) {
                if (layout.get(row).charAt(col) != 'D') {
                    continue;
                }
                if (index < names.size()) {
                    String name = names.get(index++);
                    inventory.setItem(row * 9 + col, item(icon, Map.of("arena", name,
                            "arena_status", status(name))));
                } else {
                    inventory.setItem(row * 9 + col, null);
                }
            }
        }
    }

    private ItemStack item(AdminGuiConfig.Icon icon, Map<String, String> placeholders) {
        ItemStack stack = new ItemStack(icon.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(render(icon.name(), placeholders).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : icon.lore()) {
            lore.add(render(line, placeholders).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        if (icon.customModelData() > 0) {
            meta.setCustomModelData(icon.customModelData());
        }
        if (icon.enchant()) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
        }
        if (icon.hideFlag()) {
            meta.addItemFlags(ItemFlag.values());
        } else if (icon.hideEnchant()) {
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private String arenaAt(Session session, int slot) {
        AdminGuiConfig.Snapshot config = guiConfig.current();
        int index = 0;
        for (int row = 0; row < config.layout().size(); row++) {
            for (int col = 0; col < 9; col++) {
                if (config.layout().get(row).charAt(col) != 'D') {
                    continue;
                }
                if (row * 9 + col == slot) {
                    return index < session.arenaNames().size() ? session.arenaNames().get(index) : null;
                }
                index++;
            }
        }
        return null;
    }

    private static String actionAt(Screen screen, int slot, AdminGuiConfig.Snapshot config) {
        List<String> layout = switch (screen) {
            case MAIN -> config.layout();
            case EDITOR -> config.editorLayout();
            case CONFIRM -> config.confirmLayout();
        };
        int row = slot / 9;
        int col = slot % 9;
        if (row < 0 || row >= layout.size()) {
            return null;
        }
        AdminGuiConfig.Icon icon = config.icons().get(layout.get(row).charAt(col));
        return icon == null ? null : icon.action();
    }

    private String status(String arena) {
        if (game.view(arena).isPresent()) {
            return PlainTextComponentSerializer.plainText().serialize(messages.renderRaw("gui-status-running", Map.of()));
        }
        if (arenas.generatedArenas().stream().anyMatch(g -> g.definition().name().equalsIgnoreCase(arena))) {
            return PlainTextComponentSerializer.plainText().serialize(messages.renderRaw("gui-status-generated", Map.of()));
        }
        return PlainTextComponentSerializer.plainText().serialize(messages.renderRaw("gui-status-manual", Map.of()));
    }

    private static ArenaPoint point(Player player) {
        org.bukkit.Location location = player.getLocation();
        if (location.getWorld() == null || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())) {
            throw new IllegalArgumentException("玩家位置无效");
        }
        return new ArenaPoint(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    private Component render(String template, Map<String, String> placeholders) {
        String source = template == null ? "" : template;
        Map<String, String> values = placeholders == null ? Map.of() : placeholders;
        Map<String, String> tokens = new HashMap<>();
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String token = "HFR_GUI_TOKEN_" + index++ + "_X";
            source = source.replace("{" + entry.getKey() + "}", token);
            tokens.put(token, entry.getValue() == null ? "" : entry.getValue());
        }
        Component component = messages.parseRaw(source);
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            component = component.replaceText(TextReplacementConfig.builder().matchLiteral(entry.getKey())
                    .replacement(builder -> builder.content(entry.getValue())).build());
        }
        return component;
    }

    private enum Screen { MAIN, EDITOR, CONFIRM }

    private enum InputStage { NAME, SEED, FLOORS }

    private record InputState(InputStage stage, String arenaName, long seed) {
    }

    private static final class Session {
        private final UUID playerId;
        private final UUID token;
        private final Screen screen;
        private final String arenaName;
        private final List<String> arenaNames;
        private volatile ArenaPoint pos1;
        private volatile ArenaPoint pos2;
        private final AtomicBoolean actionBusy = new AtomicBoolean();

        private Session(UUID playerId, UUID token, Screen screen, String arenaName, List<String> arenaNames) {
            this.playerId = playerId;
            this.token = token;
            this.screen = screen;
            this.arenaName = arenaName;
            this.arenaNames = List.copyOf(arenaNames);
        }

        private Screen screen() { return screen; }
        private UUID token() { return token; }
        private String arenaName() { return arenaName; }
        private List<String> arenaNames() { return arenaNames; }
        private ArenaPoint pos1() { return pos1; }
        private ArenaPoint pos2() { return pos2; }
        private AtomicBoolean actionBusy() { return actionBusy; }
        private void pos1(ArenaPoint value) { pos1 = value; }
        private void pos2(ArenaPoint value) { pos2 = value; }
    }

    private static final class GuiHolder implements InventoryHolder {
        private final UUID playerId;
        private final UUID token;
        private final Screen screen;
        private final String arenaName;
        private Inventory inventory;

        private GuiHolder(UUID playerId, UUID token, Screen screen, String arenaName) {
            this.playerId = playerId;
            this.token = token;
            this.screen = screen;
            this.arenaName = arenaName;
        }

        private void inventory(Inventory inventory) { this.inventory = inventory; }
        private Screen screen() { return screen; }
        private UUID token() { return token; }
        private String arenaName() { return arenaName; }

        @Override
        public Inventory getInventory() { return inventory; }
    }
}
