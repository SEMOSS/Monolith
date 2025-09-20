package prerna.web.services.util;

public enum ReactorResourceCategory {
    IMPORT_MERGE("importMerge"),
    UTILITY("utility"),
    UPLOAD_UTILS("uploadUtils"),
    EXCEL_VALIDATION("excelValidation"),
    UPLOADING("uploading"),
    GRAPH("graph"),
    QUERY_STRUCT("queryStruct"),
    DATABASE_MODIFICATION("databaseModification"),
    DATA_SOURCE("dataSource"),
    FRAME("frame"),
    TASK("task"),
    TASK_OPERATIONS("taskOperations"),
    LOCAL_MASTER("localMaster"),
    OWL_META("owlMeta"),
    PANEL("panel"),
    INSIGHT("insight"),
    SAVE("save"),
    DASHBOARD("dashboard"),
    GENERAL_FRAME("generalFrame"),
    ALGORITHM("algorithm"),
    STORAGE("storage"),
    GIT("git"),
    APP_METADATA("appMetadata"),
    CLUSTER("cluster"),
    USER_SPACE("userSpace"),
    SCHEDULER("scheduler"),
    USER_TRACKING("userTracking"),
    RECOMMENDATIONS("recommendations"),
    FORMS("forms"),
    LEGACY_PLAYSHEET("legacyPlaysheet"),
    LSA("lsa"),
    GENERAL_CODE_EXECUTION("generalCodeExecution"),
    PIXEL_RECIPE("pixelRecipe"),
    WEB_SCRAPE("webScrape"),
    BITLY("bitly"),
    DATE("date"),
    LLM_AI("llmAi"),
    ADMIN("admin"),
    PROJECT("project"),
    ENGINE("engine"),
    VECTOR_DATABASE("vectorDatabase"),
    MCP("mcp"),
    ALL("all");
    
    private final String value;
    
    ReactorResourceCategory(String value) {
        this.value = value;
    }
    
    public String getValue() {
        return value;
    }
     public static ReactorResourceCategory fromString(String text) {
        for (ReactorResourceCategory category : ReactorResourceCategory.values()) {
            if (category.value.equalsIgnoreCase(text)) {
                return category;
            }
        }
        return ALL; // default fallback
    }

}
    