package cn.mcxyd.hundredfloor.game;

public record ValidationResult(boolean valid, String reason) {

    public static ValidationResult validResult() {
        return new ValidationResult(true, "");
    }

    public static ValidationResult invalid(String reason) {
        return new ValidationResult(false, reason);
    }
}
