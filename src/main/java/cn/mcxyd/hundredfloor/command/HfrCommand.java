package cn.mcxyd.hundredfloor.command;

import cn.mcxyd.hundredfloor.config.ConfigurationManager;
import cn.mcxyd.hundredfloor.config.ReloadResult;
import cn.mcxyd.hundredfloor.game.ArenaValidator;
import cn.mcxyd.hundredfloor.game.ValidationResult;
import cn.mcxyd.hundredfloor.game.model.ArenaBounds;
import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;
import cn.mcxyd.hundredfloor.service.ArenaRepository;
import cn.mcxyd.hundredfloor.service.ArenaGenerationService;
import cn.mcxyd.hundredfloor.service.GameService;
import cn.mcxyd.hundredfloor.service.MessageService;
import cn.mcxyd.hundredfloor.scheduler.TaskScheduler;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public final class HfrCommand implements CommandExecutor, TabCompleter {

    private static final String HELP = "hundredfloorrush.command.help";
    private static final String JOIN = "hundredfloorrush.command.join";
    private static final String LEAVE = "hundredfloorrush.command.leave";
    private static final String ADMIN = "hundredfloorrush.command.admin";
    private final ConfigurationManager configuration;
    private final ArenaRepository arenas;
    private final GameService game;
    private final MessageService messages;
    private final ArenaGenerationService generation;
    private final TaskScheduler scheduler;
    private final ArenaValidator validator = new ArenaValidator();

    public HfrCommand(ConfigurationManager configuration, ArenaRepository arenas, GameService game,
                      MessageService messages, ArenaGenerationService generation, TaskScheduler scheduler) {
        this.configuration = configuration;
        this.arenas = arenas;
        this.game = game;
        this.messages = messages;
        this.generation = generation;
        this.scheduler = scheduler;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(java.util.Locale.ROOT);
        return switch (sub) {
            case "help" -> help(sender);
            case "join" -> join(sender, args);
            case "leave" -> leave(sender);
            case "reload" -> reload(sender);
            case "generate", "create" -> admin(sender, args, this::generate);
            case "edit" -> admin(sender, args, this::createManual);
            case "set" -> playerContextAdmin(sender, args, this::set);
            case "pos1", "pos2" -> playerContextAdmin(sender, args, this::position);
            case "add-floor" -> playerContextAdmin(sender, args, this::addFloor);
            case "save" -> admin(sender, args, this::save);
            case "delete" -> admin(sender, args, this::delete);
            case "start" -> admin(sender, args, this::start);
            default -> {
                messages.send(sender, "unknown-command", Map.of());
                yield true;
            }
        };
    }

    private boolean help(CommandSender sender) {
        if (!sender.hasPermission(HELP)) {
            messages.send(sender, "no-permission", Map.of());
            return true;
        }
        messages.send(sender, "help.header", Map.of());
        messages.send(sender, "help.help", Map.of());
        if (sender.hasPermission(JOIN)) {
            messages.send(sender, "help.join", Map.of());
        }
        if (sender.hasPermission(LEAVE)) {
            messages.send(sender, "help.leave", Map.of());
        }
        if (sender.hasPermission(ADMIN)) {
            for (String key : List.of("reload", "create", "generate", "edit", "set", "pos1", "pos2", "add-floor", "save", "delete", "start")) {
                messages.send(sender, "help." + key, Map.of());
            }
        }
        return true;
    }

    private boolean join(CommandSender sender, String[] args) {
        if (!sender.hasPermission(JOIN)) {
            messages.send(sender, "no-permission", Map.of());
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only", Map.of());
            return true;
        }
        if (args.length < 2) {
            messages.send(sender, "usage-join", Map.of());
            return true;
        }
        // Folia 命令入口不保证已经处于玩家实体 Region；只把 UUID 和不可变名称
        // 带入调度器，在玩家所有者上下文读取/修改 Player。
        UUID playerId = player.getUniqueId();
        String arenaName = args[1];
        scheduler.runPlayer(playerId, target -> game.join(target, arenaName));
        return true;
    }

    private boolean leave(CommandSender sender) {
        if (!sender.hasPermission(LEAVE)) {
            messages.send(sender, "no-permission", Map.of());
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only", Map.of());
            return true;
        }
        scheduler.runPlayer(player.getUniqueId(), game::leave);
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "no-permission", Map.of());
            return true;
        }
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        scheduler.runAsync(() -> {
            ReloadResult result = configuration.reload();
            if (result.success()) {
                game.reloadMessages();
                scheduler.runGlobal(game::refreshProxyRegistration);
            }
            if (playerId == null) {
                scheduler.runGlobal(() -> sendReloadResult(Bukkit.getConsoleSender(), result));
            } else {
                scheduler.runPlayer(playerId, player -> sendReloadResult(player, result));
            }
        });
        return true;
    }

    private void sendReloadResult(CommandSender sender, ReloadResult result) {
        if (result.success()) {
            messages.send(sender, "reload-success", Map.of());
            if (result.identityChanged()) {
                messages.send(sender, "identity-changed", Map.of());
            }
        } else {
            messages.send(sender, "reload-failed", Map.of("reason", result.reason()));
        }
    }

    private boolean admin(CommandSender sender, String[] args, AdminAction action) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "no-permission", Map.of());
            return true;
        }
        if (!(sender instanceof Player player) && !action.consoleAllowed()) {
            messages.send(sender, "player-only", Map.of());
            return true;
        }
        try {
            action.execute(sender, args);
        } catch (CommandFailure exception) {
            messages.send(sender, exception.key(), exception.placeholders());
        } catch (IllegalArgumentException | IOException exception) {
            reportOperationFailure(sender, exception);
        } catch (RuntimeException exception) {
            // Bukkit/Paper 的实时对象或第三方实现可能抛出未预期运行时异常；
            // 指令线程不能把异常继续抛给核心，详细堆栈只写入控制台。
            reportOperationFailure(sender, exception);
        }
        return true;
    }

    /**
     * Executes commands that read a player's live location only in that player's
     * owning context. Folia commands are not guaranteed to run on the player
     * region, so the command thread must pass only a UUID and an immutable copy
     * of the argument array into the scheduler.
     */
    private boolean playerContextAdmin(CommandSender sender, String[] args, AdminAction action) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "no-permission", Map.of());
            return true;
        }
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only", Map.of());
            return true;
        }
        UUID playerId = player.getUniqueId();
        String[] immutableArgs = args == null ? new String[0] : args.clone();
        scheduler.runPlayer(playerId, target -> {
            try {
                action.execute(target, immutableArgs);
            } catch (CommandFailure exception) {
                messages.send(target, exception.key(), exception.placeholders());
            } catch (IllegalArgumentException | IOException exception) {
                reportOperationFailure(target, exception);
            } catch (RuntimeException exception) {
                reportOperationFailure(target, exception);
            }
        });
        return true;
    }

    private void createManual(CommandSender sender, String[] args) {
        requireArgs(args, 2);
        String arenaName = requireText(args[1], "arena-name-required");
        arenas.createDraft(arenaName);
        rememberDraft(sender, arenaName);
        messages.send(sender, "arena-created", Map.of("arena", arenaName));
    }

    private void generate(CommandSender sender, String[] args) {
        requireArgs(args, 2);
        String arenaName = requireText(args[1], "arena-name-required");
        long seed;
        if (args.length >= 3) {
            try {
                seed = Long.parseLong(args[2]);
            } catch (NumberFormatException exception) {
                throw failure("invalid-seed");
            }
        } else {
            seed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
        }
        int floors = configuration.game().floorCount();
        if (args.length >= 4) {
            try {
                floors = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                throw failure("invalid-floor-count");
            }
        }
        if (floors < 1 || floors > 100) {
            throw failure("floor-count-range");
        }
        messages.send(sender, "arena-generation-started", Map.of("arena", arenaName, "floors", floors, "seed", seed));
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        generation.generate(arenaName, floors, seed,
                result -> notifyGeneration(playerId, arenaName, result));
    }

    private void notifyGeneration(UUID playerId, String arenaName,
                                  ArenaGenerationService.GenerationResult result) {
        if (playerId == null) {
            scheduler.runGlobal(() -> sendGenerationResult(Bukkit.getConsoleSender(), arenaName, result));
            return;
        }
        scheduler.runPlayer(playerId, player -> sendGenerationResult(player, arenaName, result));
    }

    private void sendGenerationResult(CommandSender sender, String arenaName,
                                      ArenaGenerationService.GenerationResult result) {
        if (result.success()) {
            messages.send(sender, "arena-generated", Map.of("arena", arenaName,
                    "world", result.definition().waitingSpawn().world(), "seed", result.seed()));
        } else {
            messages.send(sender, "arena-generation-failed", Map.of("reason", result.reason()));
        }
    }

    private void set(CommandSender sender, String[] args) {
        requirePlayer(sender);
        requireArgs(args, 2);
        Player player = (Player) sender;
        ArenaRepository.ArenaDraft draft = draft(sender, args, 2);
        ArenaPoint point = point(player.getLocation());
        switch (args[1].toLowerCase(java.util.Locale.ROOT)) {
            case "waiting" -> draft.waiting(point);
            case "start" -> draft.start(point);
            case "finish" -> draft.finish(point);
            case "bounds" -> {
                ArenaPoint first = selection(sender, "pos1");
                ArenaPoint second = selection(sender, "pos2");
                if (!first.world().equals(second.world())) {
                    throw failure("bounds-different-world");
                }
                draft.bounds(new ArenaBounds(first.world(), Math.min(first.x(), second.x()),
                        Math.min(first.y(), second.y()), Math.min(first.z(), second.z()),
                        Math.max(first.x(), second.x()), Math.max(first.y(), second.y()), Math.max(first.z(), second.z())));
            }
            default -> throw failure("invalid-position-type");
        }
        messages.send(sender, "position-set", Map.of("type", args[1]));
    }

    private void position(CommandSender sender, String[] args) {
        requirePlayer(sender);
        requireArgs(args, 1);
        Player player = (Player) sender;
        ArenaPoint current = point(player.getLocation());
        if (args[0].equalsIgnoreCase("pos1")) {
            positions.compute(player.getUniqueId(), (ignored, holder) ->
                    holder == null ? new PositionHolder(current, null) : holder.withFirst(current));
        } else {
            positions.compute(player.getUniqueId(), (ignored, holder) ->
                    holder == null ? new PositionHolder(null, current) : holder.withSecond(current));
        }
        messages.send(sender, "position-set", Map.of("type", args[0]));
    }

    private void addFloor(CommandSender sender, String[] args) {
        requirePlayer(sender);
        requireArgs(args, 1);
        // 与帮助文档的显式语法 `/hfr add-floor [名称]` 保持一致：名称位于
        // args[1]。此前使用索引 2 会在玩家已有活动草稿时悄悄写错草稿，
        // 没有活动草稿时则把显式名称误判为缺失。
        ArenaRepository.ArenaDraft draft = draft(sender, args, 1);
        if (draft.floorCount() >= 100) {
            throw failure("floor-limit");
        }
        int floor = draft.addFloor(point(((Player) sender).getLocation()));
        messages.send(sender, "floor-added", Map.of("floor", floor));
    }

    private void save(CommandSender sender, String[] args) throws IOException {
        String arenaName = resolveDraftName(sender, args, 1);
        ArenaRepository.ArenaDraft draft = draft(arenaName);
        rememberDraft(sender, arenaName);
        ArenaDefinition definition;
        try {
            definition = draft.build();
        } catch (IllegalArgumentException exception) {
            // 草稿构建失败只向玩家返回可操作的提示，详细异常由统一处理器记录。
            throw failure("draft-incomplete");
        }
        ValidationResult validation = validator.validate(definition, configuration.game().floorCount());
        if (!validation.valid()) {
            messages.send(sender, "arena-invalid", Map.of("reason", validation.reason()));
            return;
        }
        ArenaPointSnapshot snapshot = new ArenaPointSnapshot(definition);
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        scheduler.runAsync(() -> {
            try {
                arenas.saveDraftSnapshot(arenaName, snapshot.definition());
                clearDraftForAll(arenaName);
                notifyAdminResult(playerId, "arena-saved", Map.of("arena", arenaName));
            } catch (IOException | RuntimeException exception) {
                reportOperationFailure(playerId, exception);
            }
        });
    }

    private void delete(CommandSender sender, String[] args) throws IOException {
        requireArgs(args, 2);
        String arenaName = requireText(args[1], "arena-name-required");
        UUID playerId = sender instanceof Player player ? player.getUniqueId() : null;
        scheduler.runAsync(() -> {
            try {
                // 取消生成和读取待删除快照都放在同一个异步链中，避免在玩家/区域线程
                // 执行文件操作；条件删除可防止旧的 delete 请求误删后来创建的地图。
                boolean cancelledGeneration = generation.cancel(arenaName);
                ArenaDefinition expected = arenas.find(arenaName).orElse(null);
                if (expected == null) {
                    if (cancelledGeneration) {
                        clearDraftForAll(arenaName);
                        notifyAdminResult(playerId, "arena-deleted", Map.of("arena", arenaName));
                    } else {
                        notifyAdminResult(playerId, "arena-missing", Map.of("arena", arenaName));
                    }
                    return;
                }
                if (!arenas.deleteIfUnchanged(arenaName, expected)) {
                    notifyAdminResult(playerId, "arena-delete-conflict", Map.of("arena", arenaName));
                    return;
                }
                clearDraftForAll(arenaName);
                notifyAdminResult(playerId, "arena-deleted", Map.of("arena", arenaName));
            } catch (IOException | RuntimeException exception) {
                reportOperationFailure(playerId, exception);
            }
        });
    }

    private void start(CommandSender sender, String[] args) {
        requireArgs(args, 2);
        String arenaName = requireText(args[1], "arena-name-required");
        // 比赛状态由全局 tick 推进；强制开始也在 Global 上下文执行，避免在
        // Folia 命令线程直接触碰跨区域的会话状态。
        scheduler.runGlobal(() -> game.start(arenaName));
        if (sender instanceof Player player) {
            scheduler.runPlayer(player.getUniqueId(), target ->
                    messages.send(target, "game-start-requested", Map.of("arena", arenaName)));
        } else {
            scheduler.runGlobal(() -> messages.send(sender, "game-start-requested", Map.of("arena", arenaName)));
        }
    }

    /**
     * Resolves the draft name for commands that support the README shorthand.
     * An explicit name always wins and becomes the player's active draft; when it
     * is omitted, the last draft selected by this player is used.
     */
    private String resolveDraftName(CommandSender sender, String[] args, int explicitIndex) {
        String explicit = args.length > explicitIndex ? args[explicitIndex].trim() : "";
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if (sender instanceof Player player) {
            String remembered = activeDrafts.get(player.getUniqueId());
            if (remembered != null && !remembered.isBlank()) {
                return remembered;
            }
            throw failure("draft-required");
        }
        throw failure("arena-required");
    }

    private ArenaRepository.ArenaDraft draft(CommandSender sender, String[] args, int explicitIndex) {
        String arenaName = resolveDraftName(sender, args, explicitIndex);
        ArenaRepository.ArenaDraft draft = draft(arenaName);
        rememberDraft(sender, arenaName);
        return draft;
    }

    private ArenaRepository.ArenaDraft draft(String arenaName) {
        return arenas.draft(arenaName).orElseThrow(() ->
                failure("draft-not-found", Map.of("arena", arenaName)));
    }

    private ArenaPoint selection(CommandSender sender, String name) {
        PositionHolder holder = positions.get(((Player) sender).getUniqueId());
        ArenaPoint selected = holder == null ? null
                : (name.equals("pos1") ? holder.first() : holder.second());
        if (selected == null) {
            throw failure("selection-required", Map.of("position", name));
        }
        return selected;
    }

    private static ArenaPoint point(Location location) {
        if (location == null || location.getWorld() == null
                || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ()) || !Float.isFinite(location.getYaw())
                || !Float.isFinite(location.getPitch())) {
            throw failure("teleport-failed");
        }
        return new ArenaPoint(location.getWorld().getName(), location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }

    private static void requirePlayer(CommandSender sender) {
        if (!(sender instanceof Player)) {
            throw failure("player-only");
        }
    }

    private static void requireArgs(String[] args, int length) {
        if (args.length < length) {
            throw failure("arguments-missing");
        }
    }

    private static String requireText(String value, String messageKey) {
        if (value == null || value.isBlank()) {
            throw failure(messageKey);
        }
        String trimmed = value.trim();
        if (messageKey.equals("arena-name-required")
                && (trimmed.length() > 64 || trimmed.indexOf('.') >= 0
                || trimmed.chars().anyMatch(Character::isISOControl))) {
            throw failure("arena-name-invalid");
        }
        return trimmed;
    }

    private void reportOperationFailure(CommandSender sender, Throwable exception) {
        Bukkit.getLogger().log(Level.WARNING, "管理员指令操作失败", exception);
        messages.send(sender, "operation-failed-generic", Map.of());
    }

    private void reportOperationFailure(UUID playerId, Throwable exception) {
        Bukkit.getLogger().log(Level.WARNING, "管理员指令异步操作失败", exception);
        notifyAdminResult(playerId, "operation-failed-generic", Map.of());
    }

    private void rememberDraft(CommandSender sender, String arenaName) {
        if (sender instanceof Player player) {
            activeDrafts.put(player.getUniqueId(), arenaName);
        }
    }

    private void clearDraftForAll(String arenaName) {
        if (arenaName == null) {
            return;
        }
        activeDrafts.forEach((playerId, current) -> {
            if (current.equalsIgnoreCase(arenaName)) {
                activeDrafts.remove(playerId, current);
            }
        });
    }

    /**
     * Clears all command-only state for a player. The quit listener and plugin
     * shutdown should call this method so UUID-keyed editing state cannot grow.
     */
    public void clearPlayerState(UUID playerId) {
        if (playerId == null) {
            return;
        }
        positions.remove(playerId);
        activeDrafts.remove(playerId);
    }

    /** Clears all transient command state during plugin shutdown or reload. */
    public void clearTransientState() {
        positions.clear();
        activeDrafts.clear();
    }

    private void notifyAdminResult(UUID playerId, String key, Map<String, ?> placeholders) {
        if (playerId == null) {
            scheduler.runGlobal(() -> messages.send(Bukkit.getConsoleSender(), key, placeholders));
        } else {
            scheduler.runPlayer(playerId, player -> messages.send(player, key, placeholders));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>();
            if (sender.hasPermission(HELP)) {
                values.add("help");
            }
            if (sender.hasPermission(JOIN)) {
                values.add("join");
            }
            if (sender.hasPermission(LEAVE)) {
                values.add("leave");
            }
            if (sender.hasPermission(ADMIN)) {
                values.addAll(List.of("reload", "create", "generate", "edit", "set", "pos1", "pos2", "add-floor", "save", "delete", "start"));
            }
            return partial(values, args[0]);
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        if (args.length == 2 && sub.equals("join") && sender.hasPermission(JOIN)) {
            return partial(arenas.all().stream().map(definition -> definition.name()).toList(), args[1]);
        }
        if (args.length == 2 && sub.equals("set") && sender.hasPermission(ADMIN)) {
            return partial(List.of("waiting", "start", "finish", "bounds"), args[1]);
        }
        if (args.length == 2 && sub.equals("add-floor") && sender.hasPermission(ADMIN)) {
            return partial(arenas.all().stream().map(definition -> definition.name()).toList(), args[1]);
        }
        if (args.length == 2 && List.of("save", "delete", "start").contains(sub)
                && sender.hasPermission(ADMIN)) {
            return partial(arenas.all().stream().map(definition -> definition.name()).toList(), args[1]);
        }
        if (args.length == 3 && sub.equals("set") && sender.hasPermission(ADMIN)) {
            return partial(arenas.all().stream().map(definition -> definition.name()).toList(), args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> partial(List<String> values, String prefix) {
        return values.stream().filter(value -> value.regionMatches(true, 0, prefix, 0, prefix.length())).sorted().toList();
    }

    private final ConcurrentMap<UUID, PositionHolder> positions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> activeDrafts = new ConcurrentHashMap<>();

    private interface AdminAction {
        void execute(CommandSender sender, String[] args) throws IOException;

        default boolean consoleAllowed() {
            return true;
        }
    }

    private record PositionHolder(ArenaPoint first, ArenaPoint second) {
        private PositionHolder withFirst(ArenaPoint point) {
            return new PositionHolder(point, second);
        }

        private PositionHolder withSecond(ArenaPoint point) {
            return new PositionHolder(first, point);
        }
    }

    private record ArenaPointSnapshot(cn.mcxyd.hundredfloor.game.model.ArenaDefinition definition) {
    }

    private static CommandFailure failure(String key) {
        return new CommandFailure(key, Map.of());
    }

    private static CommandFailure failure(String key, Map<String, ?> placeholders) {
        return new CommandFailure(key, placeholders);
    }

    private static final class CommandFailure extends IllegalArgumentException {
        private final String key;
        private final Map<String, ?> placeholders;

        private CommandFailure(String key, Map<String, ?> placeholders) {
            super(key);
            this.key = key;
            this.placeholders = Map.copyOf(placeholders);
        }

        private String key() {
            return key;
        }

        private Map<String, ?> placeholders() {
            return placeholders;
        }
    }
}
