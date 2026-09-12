package cn.mcxyd.hundredfloor.scheduler;

public final class ServerPlatform {

    private static volatile Boolean IS_FOLIA;

    private ServerPlatform() {
    }

    public static boolean isFolia() {
        if (IS_FOLIA != null) {
            return IS_FOLIA;
        }
        synchronized (ServerPlatform.class) {
            if (IS_FOLIA == null) {
                try {
                    Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
                    IS_FOLIA = true;
                } catch (ClassNotFoundException exception) {
                    IS_FOLIA = false;
                }
            }
            return IS_FOLIA;
        }
    }
}
