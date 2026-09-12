package cn.mcxyd.hundredfloor.config;

public record ReloadResult(boolean success, String reason, boolean identityChanged) {

    public static ReloadResult success(boolean identityChanged) {
        return new ReloadResult(true, "", identityChanged);
    }

    public static ReloadResult failure(String reason) {
        return new ReloadResult(false, reason, false);
    }
}
