package com.gtnewhorizons.neid;

public class Common {

    public static boolean thermosTainted;
    public static boolean ultramineTainted;

    static {
        try {
            Class.forName("org.bukkit.World");
            Common.thermosTainted = true;
        } catch (ClassNotFoundException e) {
            Common.thermosTainted = false;
        }

        try {
            Class.forName("org.ultramine.server.chunk.alloc.MemSlot");
            Common.ultramineTainted = true;
        } catch (ClassNotFoundException e) {
            Common.ultramineTainted = false;
        }
    }
}
