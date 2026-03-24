package net.shasankp000.Util;

public class ToolStrength {

    public static float getToolMultiplier(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return 1.0f;
        }

        toolName = toolName.toLowerCase();
        if (toolName.contains(":")) {
            toolName = toolName.substring(toolName.indexOf(':') + 1);
        }

        if (toolName.contains("wooden")) toolName = toolName.replace("wooden", "wood");

        if (toolName.contains("wood")) return 0.5f;
        if (toolName.contains("stone")) return 0.75f;
        if (toolName.contains("iron")) return 1.0f;
        if (toolName.contains("gold")) return 0.9f;
        if (toolName.contains("diamond")) return 1.9f;
        if (toolName.contains("netherite")) return 2.0f;
        return 1.0f; // default
    }

}
