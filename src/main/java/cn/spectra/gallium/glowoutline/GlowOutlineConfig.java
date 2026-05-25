package cn.spectra.gallium.glowoutline;

public final class GlowOutlineConfig {

    public enum Group { GLOBAL, RENDER_TARGET }

    public enum Toggle {
        ENABLED("enabled", "glow_enabled", Group.GLOBAL),
        FIRST_PERSON("first_person", "glow_first_person", Group.RENDER_TARGET),
        THIRD_PERSON("third_person", "glow_third_person", Group.RENDER_TARGET),
        OTHER_ENTITIES("other_entities", "glow_other_entities", Group.RENDER_TARGET),
        DROPPED_ITEMS("dropped_items", "glow_dropped_items", Group.RENDER_TARGET),
        ARMOR("armor", "glow_armor", Group.RENDER_TARGET),
        GUI("gui", "glow_gui", Group.RENDER_TARGET);

        private final String jsonKey;
        private final String sodiumId;
        private final Group group;
        private volatile boolean value = true;

        Toggle(String jsonKey, String sodiumId, Group group) {
            this.jsonKey = jsonKey;
            this.sodiumId = sodiumId;
            this.group = group;
        }

        public boolean get() { return value; }
        public void set(boolean v) { value = v; }
        public String jsonKey() { return jsonKey; }
        public String sodiumId() { return sodiumId; }
        public Group group() { return group; }
    }

    private GlowOutlineConfig() {}

    public static boolean isEnabled() { return Toggle.ENABLED.get(); }
    public static void setEnabled(boolean v) { Toggle.ENABLED.set(v); }

    public static boolean isFirstPerson() { return Toggle.FIRST_PERSON.get(); }
    public static void setFirstPerson(boolean v) { Toggle.FIRST_PERSON.set(v); }

    public static boolean isThirdPerson() { return Toggle.THIRD_PERSON.get(); }
    public static void setThirdPerson(boolean v) { Toggle.THIRD_PERSON.set(v); }

    public static boolean isOtherEntities() { return Toggle.OTHER_ENTITIES.get(); }
    public static void setOtherEntities(boolean v) { Toggle.OTHER_ENTITIES.set(v); }

    public static boolean isDroppedItems() { return Toggle.DROPPED_ITEMS.get(); }
    public static void setDroppedItems(boolean v) { Toggle.DROPPED_ITEMS.set(v); }

    public static boolean isArmor() { return Toggle.ARMOR.get(); }
    public static void setArmor(boolean v) { Toggle.ARMOR.set(v); }

    public static boolean isGui() { return Toggle.GUI.get(); }
    public static void setGui(boolean v) { Toggle.GUI.set(v); }
}
