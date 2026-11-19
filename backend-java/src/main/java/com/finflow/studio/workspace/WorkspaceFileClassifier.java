package com.finflow.studio.workspace;

import java.util.Locale;
import java.util.Set;

public final class WorkspaceFileClassifier {
    private static final Set<String> DATA_EXTENSIONS = Set.of(
            "csv", "tsv", "json", "parquet", "xlsx", "xls", "xlsm"
    );
    private static final Set<String> OFFICE_EXTENSIONS = Set.of("doc", "docx", "ppt", "pptx");

    private WorkspaceFileClassifier() {}

    public static String resourceType(String name, String mediaType) {
        var extension = extension(name);
        var normalizedMediaType = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
        if (DATA_EXTENSIONS.contains(extension) || normalizedMediaType.equals("application/vnd.apache.parquet")) {
            return "DATA_FILE";
        }
        if (OFFICE_EXTENSIONS.contains(extension)) return "OFFICE_FILE";
        return "KNOWLEDGE_FILE";
    }

    public static String group(String name, String mediaType) {
        return resourceType(name, mediaType).equals("DATA_FILE") ? "DATA" : "KNOWLEDGE";
    }

    private static String extension(String name) {
        if (name == null) return "";
        var normalized = name.toLowerCase(Locale.ROOT);
        var separator = normalized.lastIndexOf('.');
        return separator < 0 ? "" : normalized.substring(separator + 1);
    }
}
