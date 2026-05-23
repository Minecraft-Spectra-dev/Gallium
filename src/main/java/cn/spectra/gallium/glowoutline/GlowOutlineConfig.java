package cn.spectra.gallium.glowoutline;

public class GlowOutlineConfig {
    private static volatile boolean enabled = true;
    private static volatile boolean firstPerson = true;
    private static volatile boolean thirdPerson = true;
    private static volatile boolean otherEntities = true;
    private static volatile boolean droppedItems = true;
    private static volatile boolean armor = true;
    private static volatile boolean gui = true;

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

    public static boolean isGui() { return gui; }
    public static void setGui(boolean v) { gui = v; }
}
