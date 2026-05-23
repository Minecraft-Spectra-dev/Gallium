package cn.spectra.gallium.glowoutline;

public class GlowOutlineConfig {
    private static boolean enabled = true;
    private static boolean firstPerson = true;
    private static boolean thirdPerson = true;
    private static boolean otherEntities = true;
    private static boolean droppedItems = true;
    private static boolean armor = true;

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) { enabled = v; }

    public static boolean isFirstPerson() { return firstPerson; }
    public static void setFirstPerson(boolean v) { firstPerson = v; }

    public static boolean isThirdPerson() { return thirdPerson; }
    public static void setThirdPerson(boolean v) { thirdPerson = v; }

    public static boolean isOtherEntities() { return otherEntities; }
    public static void setOtherEntities(boolean v) { otherEntities = v; }

    public static boolean isDroppedItems() { return droppedItems; }
    public static void setDroppedItems(boolean v) { droppedItems = v; }

    public static boolean isArmor() { return armor; }
    public static void setArmor(boolean v) { armor = v; }
}
