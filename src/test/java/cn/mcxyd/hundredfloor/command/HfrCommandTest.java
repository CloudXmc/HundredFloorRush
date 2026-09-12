package cn.mcxyd.hundredfloor.command;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HfrCommandTest {

    @Test
    void tabCompletionHidesCommandsWithoutPermission() {
        HfrCommand command = new HfrCommand(null, null, null, null, null, null);

        assertTrue(command.onTabComplete(senderWithPermissions(Set.of()), null, "hfr", new String[]{""}).isEmpty());
        assertEquals(java.util.List.of("join"),
                command.onTabComplete(senderWithPermissions(Set.of("hundredfloorrush.command.join")),
                        null, "hfr", new String[]{""}));
    }

    @Test
    void tabCompletionShowsAdministrativeCommandsOnlyToAdministrators() {
        HfrCommand command = new HfrCommand(null, null, null, null, null, null);

        var values = command.onTabComplete(senderWithPermissions(
                        Set.of("hundredfloorrush.command.help", "hundredfloorrush.command.admin")),
                null, "hfr", new String[]{""});

        assertTrue(values.contains("help"));
        assertTrue(values.contains("create"));
        assertTrue(values.contains("set"));
        assertTrue(!values.contains("join"));
    }

    private static CommandSender senderWithPermissions(Set<String> permissions) {
        return (CommandSender) Proxy.newProxyInstance(
                HfrCommandTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return permissions.contains(args == null ? "" : String.valueOf(args[0]));
                    }
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    }
                    if (method.getReturnType() == byte.class) {
                        return (byte) 0;
                    }
                    if (method.getReturnType() == short.class) {
                        return (short) 0;
                    }
                    if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    if (method.getReturnType() == long.class) {
                        return 0L;
                    }
                    if (method.getReturnType() == float.class) {
                        return 0F;
                    }
                    if (method.getReturnType() == double.class) {
                        return 0D;
                    }
                    if (method.getReturnType() == char.class) {
                        return '\0';
                    }
                    return null;
                });
    }
}
