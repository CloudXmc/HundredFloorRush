package cn.mcxyd.hundredfloor.scheduler;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public interface TaskScheduler extends AutoCloseable {

    void runEntity(Entity entity, Runnable task);

    void runPlayer(UUID playerId, Consumer<Player> task);

    TaskHandle runEntityDelayed(Entity entity, Runnable task, long delayTicks);

    TaskHandle runEntityTimer(Entity entity, Runnable task, long delayTicks, long periodTicks);

    void runRegion(Location location, Runnable task);

    TaskHandle runRegionDelayed(Location location, Runnable task, long delayTicks);

    TaskHandle runRegionDelayed(String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks);

    void runGlobal(Runnable task);

    TaskHandle runGlobalDelayed(Runnable task, long delayTicks);

    TaskHandle runGlobalTimer(Runnable task, long delayTicks, long periodTicks);

    void runAsync(Runnable task);

    TaskHandle runAsyncDelayed(Runnable task, long delayMillis);

    TaskHandle runAsyncTimer(Runnable task, long delayMillis, long periodMillis);

    void teleport(UUID playerId, String worldName, double x, double y, double z,
                  float yaw, float pitch, BiConsumer<Player, Boolean> completion);

    /**
     * Starts a teleport only for the supplied Player incarnation.  This
     * overload prevents a delayed UUID lookup from accidentally moving a
     * player who disconnected and reconnected with the same UUID.
     */
    default void teleport(Player expectedPlayer, String worldName, double x, double y, double z,
                           float yaw, float pitch, BiConsumer<Player, Boolean> completion) {
        // A null callback cannot observe the result and is rejected by the
        // UUID overload.  Return before wrapping it so a late completion can
        // never dereference a null consumer.
        if (completion == null) {
            return;
        }
        if (expectedPlayer == null) {
            completion.accept(null, false);
            return;
        }
        teleport(expectedPlayer.getUniqueId(), worldName, x, y, z, yaw, pitch, (target, success) -> {
            if (target != expectedPlayer) {
                completion.accept(null, false);
            } else {
                completion.accept(target, success);
            }
        });
    }

    @Override
    void close();
}
