package prerna.web.services.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class ReactorResourceGroups {

    public static void createImportMergeReactorHash(Set<String> reactorHash) {
        // This method is intentionally left blank.
        // Its purpose is to ensure that all reactor classes are loaded into the JVM,
        // which in turn populates the ReactorFactory.reactorHash via static
        // initializers.
        reactorHash.add("Import");
        reactorHash.add("Merge");
    }

    private static void createUtilyReactorHash(Set<String> reactorHash) {
        reactorHash.add("AddOperationAlias");
        reactorHash.add("VariableExists");
        reactorHash.add("RemoveVariable");
        reactorHash.add("SendEmail");
        reactorHash.add("BackupDatabase");
        reactorHash.add("ExportDatabase");
        reactorHash.add("DeleteDatabase");
        reactorHash.add("ImageCapture");
        reactorHash.add("Help");
        reactorHash.add("help");
        reactorHash.add("DatabaseProfile");
        reactorHash.add("DatabaseColumnUnique");
        reactorHash.add("ExternalDatabaseProfile");
        reactorHash.add("GetRequest");
        reactorHash.add("PostRequest");
        reactorHash.add("CheckRPackages");
        // TODO: fix reactorHash.add("CheckPyPackages");
        reactorHash.add("CheckRecommendOptimization");
        reactorHash.add("PredictExcelRangeMetadata");
        reactorHash.add("DeleteInsightCache");
        reactorHash.add("WidgetT");
        reactorHash.add("GetUserInfo");
        // Virtual environment management
        // TODO: fix reactorHash.add("ActivateVirtualEnv");
        // TODO: reactorHash.add("AddPackageToVenv");
        // reactorHash.add("RemovePackageFromVenv");
        // reactorHash.add("ListPackagesInVirtualEnv");
        // Validation
        // reactorHash.add("ValidateR");
        // reactorHash.add("ValidateProjectDependencies");
        reactorHash.add("ValidateUserProjectDependencies");
    }

    private static void createUploadUtilsReactorHash(Set<String> reactorHash) {
        reactorHash.add("CheckHeaders");
        reactorHash.add("PredictDataTypes");
        reactorHash.add("PredictExcelDataTypes");
        reactorHash.add("PredictMetamodel");
        reactorHash.add("ParseMetamodel");
        reactorHash.add("ExtractAppMeta");
        reactorHash.add("NLPInstanceCache");
    }

    private static void createExcelDataValidationReactorHash(Set<String> reactorHash) {
        reactorHash.add("GetExcelForm");
    }

    private static void createUploadingReactorHash(Set<String> reactorHash) {
        reactorHash.add("ExternalJdbcSchema");
        reactorHash.add("ExternalJdbcTablesAndViews");
        reactorHash.add("RdbmsUploadTableData");
        reactorHash.add("RdbmsUploadExcelData");
        reactorHash.add("RdbmsExternalUpload");
        reactorHash.add("RdbmsCsvUpload");
        reactorHash.add("RdbmsLoaderSheetUpload");
        reactorHash.add("RdfCsvUpload");
        reactorHash.add("RdfLoaderSheetUpload");
        reactorHash.add("TinkerCsvUpload");
        // Additional uploading reactors
        // reactorHash.add("PredictParquetDataTypes");
        // Replacement uploading reactors
        reactorHash.add("TinkerReplaceDatabaseCsvUpload");
        reactorHash.add("RdbmsReplaceDatabaseCsvUpload");
        reactorHash.add("RdbmsReplaceDatabaseExcelUpload");
        reactorHash.add("RdbmsReplaceDatabaseLoaderSheetUpload");
        reactorHash.add("RdbmsReplaceDatabaseUploadTable");
        // reactorHash.add("RdfReplaceDatabaseCsvUpload");
        reactorHash.add("RdfReplaceDatabaseLoaderSheetUpload");
        // reactorHash.add("RCsvUpload");
        // reactorHash.add("RReplaceDatabaseCsvUpload");
    }

    private static void createGraphReactorHash(Set<String> reactorHash) {
        reactorHash.add("GetGraphProperties");
        reactorHash.add("GetGraphMetaModel");
        reactorHash.add("CreateExternalGraphDatabase");
        reactorHash.add("CreateExternalNeo4jDatabase");
        // datastax graph reactors
        reactorHash.add("GetDSEGraphProperties");
        reactorHash.add("GetDSEGraphMetaModel");
        reactorHash.add("CreateExternalDSEGraphDatabase");
        // janus graph reactors
        reactorHash.add("GetJanusGraphProperties");
        reactorHash.add("GetJanusGraphMetaModel");
        reactorHash.add("CreateJanusGraphDatabase");
    }

    private static void createQueryStructReactorHash(Set<String> reactorHash) {
        reactorHash.add("With");
        reactorHash.add("Select");
        reactorHash.add("SelectTable");
        reactorHash.add("PSelect");
        reactorHash.add("PCol");
        reactorHash.add("Mean");
        reactorHash.add("Average");
        reactorHash.add("UniqueAverage");
        reactorHash.add("UniqueMean");
        reactorHash.add("Sum");
        reactorHash.add("UniqueSum");
        reactorHash.add("Max");
        reactorHash.add("Min");
        reactorHash.add("Median");
        reactorHash.add("StandardDeviation");
        reactorHash.add("Count");
        reactorHash.add("UniqueCount");
        reactorHash.add("GroupConcat");
        reactorHash.add("UniqueGroupConcat");
        reactorHash.add("Lower");
        reactorHash.add("Group");
        reactorHash.add("GroupBy");
        reactorHash.add("Sort");
        reactorHash.add("SortBy");
        reactorHash.add("Order");
        reactorHash.add("Limit");
        reactorHash.add("Offset");
        reactorHash.add("Join");
        reactorHash.add("Filter");
        reactorHash.add("RegexFilter");
        reactorHash.add("Having");
        reactorHash.add("Query");
        reactorHash.add("Distinct");
        reactorHash.add("ImplicitFilterOverride");
        reactorHash.add("QueryAll");
        // Additional query operators
        // TODO: fix these reactors
        /*
         * reactorHash.add("Between");
         * reactorHash.add("Coalesce");
         * reactorHash.add("Cast");
         * reactorHash.add("Union");
         * reactorHash.add("Subquery");
         * reactorHash.add("SubqueryJoin");
         * reactorHash.add("As");
         * reactorHash.add("Assignment");
         * reactorHash.add("Context");
         * reactorHash.add("ConvertToQuery");
         * reactorHash.add("SubQueryExpression");
         * reactorHash.add("Substring");
         */
    }

    private static void createDatabaseModificationReactorHash(Set<String> reactorHash) {
        reactorHash.add("Insert");
        reactorHash.add("Delete");
        reactorHash.add("Update");
        reactorHash.add("ExecQuery");
    }

    private static void createDataSourceReactorHash(Set<String> reactorHash) {
        reactorHash.add("Database");
        reactorHash.add("AuditDatabase");
        reactorHash.add("API");
        reactorHash.add("FileRead");
        reactorHash.add("JdbcSource");
        reactorHash.add("DirectJDBCConnection");
        reactorHash.add("URLSource");
        // drop box
        reactorHash.add("DropBoxUploader");
        reactorHash.add("DropBoxListFiles");
        reactorHash.add("DropBoxFileRetriever");
        // one drive
        reactorHash.add("OneDriveUploader");
        reactorHash.add("OneDriveListFiles");
        reactorHash.add("OneDriveFileRetriever");
        // google
        reactorHash.add("GoogleUploader");
        reactorHash.add("GoogleListFiles");
        reactorHash.add("GoogleFileRetriever");
        // share point
        reactorHash.add("SharePointListFiles");
        reactorHash.add("SharePointFileRetriever");
        reactorHash.add("SharePointSiteSelector");
        reactorHash.add("SharePointDriveSelector");
        reactorHash.add("SharePointWebDavPull");
        // survey monkey
        reactorHash.add("SurveyMonkeyListSurveys");
        reactorHash.add("NaturalLanguageSearch");
        // file sources
        reactorHash.add("RDFFileSource");
        reactorHash.add("Asset");
        reactorHash.add("FileReference");
        // snowflake
        // TODO: fix
        // reactorHash.add("SnowflakeCopyInto");
        // reactorHash.add("SnowflakeListFiles");
        // reactorHash.add("SnowflakePut");
        // reactorHash.add("SnowflakeRemoveFiles");
        // postgres
        // reactorHash.add("PostgresCopy");
    }

    private static void createFrameReactorHash(Set<String> reactorHash) {
        reactorHash.add("Frame");
        reactorHash.add("CreateFrame");
        reactorHash.add("FrameType");
        reactorHash.add("Convert");
        reactorHash.add("GenerateFrameFromRVariable");
        reactorHash.add("GenerateFrameFromPyVariable");
        reactorHash.add("GenerateH2FrameFromRVariable");
    }

    private static void createTaskReactorHash(Set<String> reactorHash) {
        reactorHash.add("Iterate");
        reactorHash.add("Task");
        reactorHash.add("ResetTask");
        reactorHash.add("ResetAll");
        reactorHash.add("RemoveTask");
        reactorHash.add("Collect");
        reactorHash.add("CollectAll");
        reactorHash.add("CollectGraph");
        reactorHash.add("GrabScalarElement");
        reactorHash.add("AsTask");
        reactorHash.add("EmptyData");
        reactorHash.add("CollectMeta");
        reactorHash.add("Format");
        reactorHash.add("TaskOptions");
        reactorHash.add("AutoTaskOptions");
        reactorHash.add("ToCsv");
        reactorHash.add("ToTsv");
        reactorHash.add("ToTxt");
        reactorHash.add("ToExcel");
        reactorHash.add("ToDatabase");
        reactorHash.add("ToLoaderSheet");
        reactorHash.add("ToPdf");
        reactorHash.add("ToPPT");
        reactorHash.add("ToPostgresCopy");
        reactorHash.add("ToXml");
        reactorHash.add("CollectGGPlot");
        reactorHash.add("CollectNewCol");
        reactorHash.add("CollectNewTemporalCol");
        reactorHash.add("CollectPivot");
        reactorHash.add("CollectSeaborn");
        reactorHash.add("CollectVizNetwork");
        reactorHash.add("NativeCollectNewCol");
    }

    private static void createTaskOperationsReactorHash(Set<String> reactorHash) {
        reactorHash.add("CodeLambda");
        reactorHash.add("FlatMapLambda");
        reactorHash.add("FilterLambda");
        // TODO: fix
        // reactorHash.add("MapLambda");
        // reactorHash.add("MapList");
        // reactorHash.add("MapMap");
        reactorHash.add("ToNumericType");
        reactorHash.add("ToUrlType");
        reactorHash.add("TransposeRows");
        reactorHash.add("ApplyFormatting");
    }

    private static void createLocalMasterReactorHash(Set<String> reactorHash) {
        reactorHash.add("GetDatabaseList");
        reactorHash.add("GetDatabaseConcepts");
        reactorHash.add("GetTraversalOptions");
        reactorHash.add("GetDatabaseMetamodel");
        reactorHash.add("GetConceptProperties");
        // NEW FEDERATE
        reactorHash.add("GetDatabaseConnections");
        reactorHash.add("GetDatabaseTableStructure");
        reactorHash.add("GetSpecificConceptProperties");
        reactorHash.add("FuzzyMatches");
        reactorHash.add("FuzzyMerge");
        // deprecated
        reactorHash.add("FederationBlend");
        reactorHash.add("FederationBestMatches");
    }

    private static void createOwlMetaReactorHash(Set<String> reactorHash) {
        reactorHash.add("ReloadDatabaseOwl");
        reactorHash.add("GetOwlMetamodel");
        reactorHash.add("GetOwlDictionary");
        // owl concepts
        reactorHash.add("AddOwlConcept");
        reactorHash.add("RemoveOwlConcept");
        // owl properties
        reactorHash.add("AddOwlProperty");
        reactorHash.add("RemoveOwlProperty");
        // owl relationships
        reactorHash.add("AddOwlRelationship");
        reactorHash.add("AddBulkOwlRelationships");
        reactorHash.add("RemoveOwlRelationship");
        // conceptual names
        reactorHash.add("EditOwlConceptConceptualName");
        reactorHash.add("EditOwlPropertyConceptualName");
        // data types
        reactorHash.add("EditOwlConceptDataType");
        reactorHash.add("EditOwlPropertyDataType");
        // logical names
        reactorHash.add("AddOwlLogicalNames");
        reactorHash.add("EditOwlLogicalNames");
        reactorHash.add("RemoveOwlLogicalNames");
        reactorHash.add("GetOwlLogicalNames");
        reactorHash.add("PredictOwlLogicalNames");
        // descriptions
        reactorHash.add("AddOwlDescription");
        reactorHash.add("EditOwlDescription");
        reactorHash.add("RemoveOwlDescription");
        reactorHash.add("GetOwlDescriptions");
        reactorHash.add("PredictOwlDescription");
        // routines to predict owl information
        reactorHash.add("FindDirectOwlRelationships");
        reactorHash.add("FindIndirectOwlRelationships");
        reactorHash.add("FindSemanticColumnOwlRelationships");
        reactorHash.add("FindSemanticInstanceOwlRelationships");
        reactorHash.add("SyncDatabaseWithLocalMaster");
        reactorHash.add("QueryTranslator");
        reactorHash.add("AllConceptualNames");
        reactorHash.add("CLPModel");
    }

    private static void createPanelReactorHash(Set<String> reactorHash) {
        reactorHash.add("InsightPanelIds");
        reactorHash.add("Panel");
        reactorHash.add("CachedPanel");
        reactorHash.add("CachedPanelClone");
        reactorHash.add("AddPanel");
        reactorHash.add("AddPanelIfAbsent");
        reactorHash.add("GetPanelId");
        reactorHash.add("ClosePanel");
        reactorHash.add("PanelExists");
        reactorHash.add("Clone");
        reactorHash.add("SetPanelLabel");
        reactorHash.add("SetPanelView");
        // panel filters
        reactorHash.add("AddPanelFilter");
        reactorHash.add("SetPanelFilter");
        reactorHash.add("UnfilterPanel");
        // panel sort
        reactorHash.add("AddPanelSort");
        reactorHash.add("SetPanelSort");
        reactorHash.add("RemovePanelSort");
        reactorHash.add("UnsortPanel");
        // panel comments
        reactorHash.add("AddPanelComment");
        reactorHash.add("UpdatePanelComment");
        reactorHash.add("RemovePanelComment");
        reactorHash.add("RetrievePanelComment");
        // panel ornaments
        reactorHash.add("AddPanelOrnaments");
        reactorHash.add("RemovePanelOrnaments");
        reactorHash.add("ResetPanelOrnaments");
        reactorHash.add("RetrievePanelOrnaments");
        // panel configuration
        reactorHash.add("AddPanelConfig");
        // panel events
        reactorHash.add("AddPanelEvents");
        reactorHash.add("RemovePanelEvents");
        reactorHash.add("ResetPanelEvents");
        reactorHash.add("RetrievePanelEvents");
        // panel position
        reactorHash.add("SetPanelPosition");
        // panel color by value
        reactorHash.add("AddPanelColorByValue");
        reactorHash.add("RetrievePanelColorByValue");
        reactorHash.add("RemovePanelColorByValue");
        reactorHash.add("GetPanelColorByValue");
        // new tab in browser
        reactorHash.add("OpenTab");
        reactorHash.add("AddPanelSortBy");
        reactorHash.add("SetPanelSortBy");
        reactorHash.add("SetMultiTypePanelSort");
        reactorHash.add("ClosePanelIfExists");
        reactorHash.add("GetPanelCollect");
        reactorHash.add("GetPanelFilterState");
        reactorHash.add("GetPanelFilters");
        reactorHash.add("GetPanelFiltersQS");
        reactorHash.add("GetPanelSort");
        reactorHash.add("GetPanelState");
        reactorHash.add("SetPanelCollect");
        reactorHash.add("SetPanelSheet");
        reactorHash.add("SetPanelState");
        reactorHash.add("MovePanel");
        reactorHash.add("ReplacePanelFilter");
        reactorHash.add("RefreshAllPanelTasks");
        reactorHash.add("RefreshPanelTask");
        reactorHash.add("RefreshPanelView");
    }

    private static void createInsightReactorHash(Set<String> reactorHash) {
        reactorHash.add("InsightRecipe");
        reactorHash.add("CurrentVariables");
        reactorHash.add("OpenInsight");
        reactorHash.add("LoadInsight");
        reactorHash.add("ReloadInsight");
        reactorHash.add("CopyInsight");
        reactorHash.add("OpenEmptyInsight");
        reactorHash.add("DropInsight");
        reactorHash.add("ClearInsight");
        reactorHash.add("InsightHandle");
        reactorHash.add("SetInsightOrnament");
        reactorHash.add("RetrieveInsightOrnament");
        reactorHash.add("UpdateInsightImage");
        reactorHash.add("GetCurrentRecipe");
        reactorHash.add("RetrieveInsightPipeline");
        reactorHash.add("GetInsightFrameStructure");
        reactorHash.add("GetInsightFrames");
        reactorHash.add("GetInsightMetaValues");
        reactorHash.add("GetInsightMetakeyOptions");
        reactorHash.add("GetInsightMetamodel");
        reactorHash.add("GetInsightParameters");
        reactorHash.add("GetInsightUserAccessRequest");
        reactorHash.add("GetInsightCachedDateTime");
        reactorHash.add("GetInsightConfig");
        reactorHash.add("GetInsightDatasources");
        reactorHash.add("SetInsightConfig");
        reactorHash.add("SetInsightGoldenLayout");
        reactorHash.add("SetInsightGraphOptions");
        reactorHash.add("SetInsightMetadata");
        reactorHash.add("SetInsightMetakeyOptions");
        reactorHash.add("SetInsightParamValue");
        reactorHash.add("SetInsightTheme");
        reactorHash.add("SetOpenInsightParamValue");
        reactorHash.add("CheckInsightNameExists");
        reactorHash.add("IsInsightParameterized");
        reactorHash.add("MakeInsightMosfet");
        reactorHash.add("ModifyInsightDatasource");
        reactorHash.add("ReadInsightTheme");
        reactorHash.add("MyOpenInsights");
        reactorHash.add("RequestInsight");
        reactorHash.add("InsightPixelList");
        reactorHash.add("InsightUsageStatistics");
        reactorHash.add("PullInsightFolderFromCloud");
        reactorHash.add("PushInsightFolderToCloud");
        reactorHash.add("ListInsightAPI");
        reactorHash.add("AddInsightAPI");
        reactorHash.add("DisableInsightAPI");
        reactorHash.add("AddInsightParameter");
        reactorHash.add("DeleteInsightParameter");
        reactorHash.add("UpdateInsightParameter");
        reactorHash.add("CopyInsightPermissions");
    }

    private static void createSaveReactorHash(Set<String> reactorHash) {
        reactorHash.add("SaveInsight");
        reactorHash.add("UpdateInsight");
        reactorHash.add("DeleteInsight");
        reactorHash.add("SetInsightName");
        reactorHash.add("SetInsightCacheable");
        reactorHash.add("SaveOwlPositions");
        reactorHash.add("SaveAppAssets");
        reactorHash.add("SaveAppBlocksJson");
        reactorHash.add("SaveAsset");
        reactorHash.add("SaveEngineAssets");
        reactorHash.add("SaveTaxScenario");
    }

    private static void createDashboardReactorHash(Set<String> reactorHash) {
        reactorHash.add("DashboardInsightConfig");
    }

    private static void createGeneralFrameReactorHash(Set<String> reactorHash) {
        reactorHash.add("FrameHeaders");
        reactorHash.add("FrameHeaderExists");
        reactorHash.add("AddFrameFilter");
        reactorHash.add("GetFrameFilters");
        reactorHash.add("SetFrameFilter");
        reactorHash.add("RemoveFrameFilter");
        reactorHash.add("ReplaceFrameFilter");
        reactorHash.add("DeleteFrameFilter");
        reactorHash.add("UnfilterFrame");
        reactorHash.add("HasDuplicates");
        reactorHash.add("CurrentFrame");
        reactorHash.add("SetCurrentFrame");
        // filter model
        reactorHash.add("FrameFilterModel");
        reactorHash.add("FrameFilterModelFilteredValues");
        reactorHash.add("FrameFilterModelVisibleValues");
        reactorHash.add("FrameFilterModelNumericRange");
        reactorHash.add("FrameProfile");
        reactorHash.add("FrameCache");
        reactorHash.add("FrameFilterEmptyValues");
        reactorHash.add("FrameFilterWithSQL");
        reactorHash.add("GetFrameDatabaseJoins");
        reactorHash.add("GetFrameFilterRange");
        reactorHash.add("GetFrameFilterState");
        reactorHash.add("GetFrameFilters");
        reactorHash.add("GetFrameFiltersQS");
        reactorHash.add("GetFrameMetamodel");
        reactorHash.add("GetFrameTableStructure");
        reactorHash.add("GetFrames");
        reactorHash.add("LastUsedFrame");
        reactorHash.add("SwapFrame");
        reactorHash.add("RemoveFrame");
        reactorHash.add("ResetFrameToOriginalName");
        reactorHash.add("ResetAllFilters");
        reactorHash.add("MergeFrames");
        reactorHash.add("CacheNativeFrame");
    }

    private static void createAlgorithmReactorHash(Set<String> reactorHash) {
        reactorHash.add("rAlg");
        reactorHash.add("RunClustering");
        reactorHash.add("RunMultiClustering");
        reactorHash.add("RunLOF");
        reactorHash.add("RunSimilarity");
        reactorHash.add("RunOutlier");
        reactorHash.add("Ratio");
        reactorHash.add("RunAnomaly");
        // X-Ray reactors
        reactorHash.add("GetXrayConfigList");
        reactorHash.add("GetXrayConfigFile");
        reactorHash.add("GetLocalSchema");
        reactorHash.add("GetXLSchema");
        reactorHash.add("GetCSVSchema");
        reactorHash.add("SemanticBlending");
        reactorHash.add("SemanticDescription");
        // similar reactors to x-ray
        reactorHash.add("GetPhysicalToLogicalMapping");
        reactorHash.add("GetPhysicalToPhysicalMapping");
        // these algorithms return viz data to the FE
        reactorHash.add("RunNumericalCorrelation");
        reactorHash.add("RunMatrixRegression");
        reactorHash.add("RunClassification");
        reactorHash.add("RunAssociatedLearning");
        // Additional algorithm reactors
        reactorHash.add("RunDataQuality");
        reactorHash.add("RunDatabaseDescriptionGenerator");
        reactorHash.add("RunDocumentSummarization");
        reactorHash.add("RunGPT2Description");
        reactorHash.add("RunImpliedInsights");
        reactorHash.add("RunKeyAttributes");
        reactorHash.add("RunSentimentAnalysis");
        reactorHash.add("RAlg");
        reactorHash.add("rAlg");
        reactorHash.add("HyperParameters");
        reactorHash.add("RunAliasMatch");
        reactorHash.add("UsabilityScore");
    }

    private static void createStorageReactorHash(Set<String> reactorHash) {
        reactorHash.add("StoreValue");
        reactorHash.add("RetrieveValue");
        reactorHash.add("GraphPlan");
        reactorHash.add("Storage");
        reactorHash.add("PullFromStorage");
        reactorHash.add("PushToStorage");
        reactorHash.add("DeleteFromStorage");
        reactorHash.add("ListStoragePath");
        reactorHash.add("ListStoragePathDetails");
        reactorHash.add("SyncLocalToStorage");
        reactorHash.add("SyncStorageToLocal");
    }

    private static void createGitReactorHash(Set<String> reactorHash) {
        reactorHash.add("InitAppRepo");
        reactorHash.add("AddAppCollaborator");
        reactorHash.add("RemoveAppCollaborator");
        reactorHash.add("SearchAppCollaborator");
        reactorHash.add("ListAppCollaborators");
        reactorHash.add("CopyAppRepo");
        reactorHash.add("DeleteAppRepo");
        reactorHash.add("DropAppRepo");
        reactorHash.add("SyncApp");
        reactorHash.add("SyncAppFiles");
        reactorHash.add("ListAppRemotes");
        reactorHash.add("ListUserApps");
        reactorHash.add("IsGit");
        reactorHash.add("Login");
        reactorHash.add("GitStatus");
        reactorHash.add("GitVersion");
        reactorHash.add("CreateAsset");
        reactorHash.add("UpdateAsset");
        reactorHash.add("DeleteAsset");
        reactorHash.add("SyncAppO");
        reactorHash.add("SyncAppFilesO");
    }

    private static void createAppMetadataReactorHash(Set<String> reactorHash) {
        reactorHash.add("MyDatabases");
        reactorHash.add("DatabaseInfo");
        reactorHash.add("DatabaseUsersReactor");
        reactorHash.add("GetAppInsights");
        reactorHash.add("GetInsights");
        reactorHash.add("AddDefaultInsights");
    }

    private static void createClusterReactorHash(Set<String> reactorHash) {
        reactorHash.add("OpenDatabase");
        reactorHash.add("CleanUpDatabases");
        reactorHash.add("Version");
    }

    private static void createUserSpaceReactorHash(Set<String> reactorHash) {
        reactorHash.add("UploadUserFile");
        reactorHash.add("UserDir");
        reactorHash.add("DeleteUserAsset");
        reactorHash.add("NewDir");
        reactorHash.add("MoveUserAsset");
    }

    private static void createSchedulerReactorHash(Set<String> reactorHash) {
        reactorHash.add("ScheduleJob");
        reactorHash.add("PauseJobTrigger");
        reactorHash.add("ListAllJobs");
        reactorHash.add("ResumeJobTrigger");
    }

    private static void createUserTrackingReactorHash(Set<String> reactorHash) {
        reactorHash.add("UpdateSemanticData");
        reactorHash.add("UpdateQueryData");
    }

    private static void createRecommendationsReactorHash(Set<String> reactorHash) {
        reactorHash.add("DatabaseRecommendations");
        reactorHash.add("VizRecommendations");
        reactorHash.add("PredictViz");
        reactorHash.add("GetDatabasesByDescription");
        reactorHash.add("UpdateNLPHistory");
        reactorHash.add("NLSQueryHelper");
    }

    private static void createFormsReactorHash(Set<String> reactorHash) {
        reactorHash.add("UpdateForm");
    }

    private static void createLegacyPlaysheetReactorHash(Set<String> reactorHash) {
        reactorHash.add("RunPlaysheetMethod");
        reactorHash.add("RunPlaysheet");
        reactorHash.add("GetPlaysheetParams");
    }

    private static void createLSAReactorHash(Set<String> reactorHash) {
        reactorHash.add("LSASpaceColumnLearned");
        reactorHash.add("RunLSILearned");
    }

    private static void createGeneralCodeExecutionReactorHash(Set<String> reactorHash) {
        reactorHash.add("Java");
        reactorHash.add("Py");
        reactorHash.add("R");
        reactorHash.add("PySource");
        reactorHash.add("RSource");
        reactorHash.add("LoadPyFromFile");
        reactorHash.add("LoadPyFromFileProjectPy");
        reactorHash.add("CancelR");
        reactorHash.add("REnableUserRecovery");
        reactorHash.add("ParallelPixelRun");
        reactorHash.add("ParallelRun");
        reactorHash.add("StopPixelExecution");
        reactorHash.add("GetConsolidatedCodeExecution");
    }

    private static void createPixelRecipeReactorHash(Set<String> reactorHash) {
        reactorHash.add("GetInsightDatasources");
        reactorHash.add("ModifyInsightDatasource");
        reactorHash.add("GetOptimizedRecipe");
    }

    private static void createWebScrapeReactorHash(Set<String> reactorHash) {
        reactorHash.add("GetTableHeader");
        reactorHash.add("GetNumTable");
    }

    private static void createBitlyReactorHash(Set<String> reactorHash) {
        reactorHash.add("badd");
        reactorHash.add("bupd");
        reactorHash.add("bdel");
        reactorHash.add("bq");
        reactorHash.add("Badd");
        reactorHash.add("Bupd");
        reactorHash.add("BDel");
        reactorHash.add("BQ");
    }

    private static void createDateReactorHash(Set<String> reactorHash) {
        reactorHash.add("DATE");
        reactorHash.add("DAY");
        reactorHash.add("WEEK");
        reactorHash.add("MONTH");
        reactorHash.add("YEAR");
        reactorHash.add("Date");
        reactorHash.add("Day");
        reactorHash.add("Month");
        reactorHash.add("Quarter");
        reactorHash.add("DayName");
        reactorHash.add("MonthName");
        reactorHash.add("DateFormat");
        reactorHash.add("DateManipulation");
        reactorHash.add("Timestamp");
    }

    private static void createLLMAIReactorHash(Set<String> reactorHash) {
        reactorHash.add("LLM");
        reactorHash.add("LLM2");
        reactorHash.add("LLMInstruct");
        reactorHash.add("Ask");
        reactorHash.add("AskPlayground");
        reactorHash.add("AskRoomPrompt");
        reactorHash.add("AskTool");
        reactorHash.add("Model");
        reactorHash.add("Vision");
        reactorHash.add("NER");
        reactorHash.add("Embeddings");
        reactorHash.add("ImageEmbeddings");
        reactorHash.add("EmbedderKeywordExtraction");
        reactorHash.add("Rerank");
        reactorHash.add("BuildModelToolsArray");
        reactorHash.add("CreateEmbeddingsFromDocuments");
        reactorHash.add("CreateEmbeddingsFromVectorCSVFile");
        reactorHash.add("GetModelAPI");
        reactorHash.add("GetModelMaxTokenLength");
        reactorHash.add("GetMyOpenAiKeyModelsList");
        reactorHash.add("AddOpenAIKey");
        reactorHash.add("TextToSQL");
        // Guardrail engines
        reactorHash.add("ExecuteGuardrailEngine");
        reactorHash.add("DetoxifyGuardrailEngine");
        reactorHash.add("GLiNERGuardrailEngine");
        reactorHash.add("GenericGuardrailInput");
        reactorHash.add("GetGuardrailEngineDefintion");
    }

    private static void createAdminReactorHash(Set<String> reactorHash) {
        reactorHash.add("AdminDatabase");
        reactorHash.add("AdminEngineInfo");
        reactorHash.add("AdminExecQuery");
        reactorHash.add("AdminExportAllUsers");
        reactorHash.add("AdminExportUserDatabasePermissions");
        reactorHash.add("AdminGetAllEngineUsage");
        reactorHash.add("AdminGetEngineMarkdown");
        reactorHash.add("AdminGetEngineSMSS");
        reactorHash.add("AdminGetEngineUsagePerProject");
        reactorHash.add("AdminGetEngineUsagePerUser");
        reactorHash.add("AdminGetProjectAvailableReactors");
        reactorHash.add("AdminGetProjectMarkdown");
        reactorHash.add("AdminGetProjectPortalDetails");
        reactorHash.add("AdminGetProjectUsage");
        reactorHash.add("AdminGetRDFMap");
        reactorHash.add("AdminGetSystemInfo");
        reactorHash.add("AdminLoadLdapUsers");
        reactorHash.add("AdminLockAccountWarning");
        reactorHash.add("AdminLockAccounts");
        reactorHash.add("AdminMyEngines");
        reactorHash.add("AdminMyProjects");
        reactorHash.add("AdminProjectInfo");
        reactorHash.add("AdminPushLocalToCloud");
        reactorHash.add("AdminRClearAllUserRserves");
        reactorHash.add("AdminReloadSocialProperties");
        reactorHash.add("AdminRemoveDuplicates");
        reactorHash.add("AdminResetPasswordRules");
        reactorHash.add("AdminUploadDatabasePermissions");
        reactorHash.add("AdminUploadUsers");
    }

    private static void createProjectReactorHash(Set<String> reactorHash) {
        reactorHash.add("CreateProject");
        reactorHash.add("OpenProject");
        reactorHash.add("ProjectInfo");
        reactorHash.add("MyProjects");
        reactorHash.add("DeleteProject");
        reactorHash.add("ExportProject");
        reactorHash.add("UploadProject");
        reactorHash.add("ExportProjectApp");
        reactorHash.add("UploadProjectApp");
        reactorHash.add("GetProjectList");
        reactorHash.add("GetProjectMarkdown");
        reactorHash.add("GetProjectMetaValues");
        reactorHash.add("GetProjectMetadata");
        reactorHash.add("GetProjectMetakeyOptions");
        reactorHash.add("GetProjectUserAccessRequest");
        reactorHash.add("GetProjectAvailableReactors");
        reactorHash.add("GetProjectAuthorizationHeader");
        reactorHash.add("GetProjectDependencies");
        reactorHash.add("GetProjectEmailSession");
        reactorHash.add("GetProjectIMAPEmailSession");
        reactorHash.add("GetProjectPOP3EmailSession");
        reactorHash.add("GetProjectPortalDetails");
        reactorHash.add("GetProjectPropertiesContent");
        reactorHash.add("GetProjectReactorStatus");
        reactorHash.add("GetProjectSMSS");
        reactorHash.add("SetProjectDependencies");
        reactorHash.add("SetProjectMetadata");
        reactorHash.add("SetProjectMetakeyOptions");
        reactorHash.add("SetProjectPropertiesContent");
        reactorHash.add("PublishProject");
        reactorHash.add("ProjectGitDetails");
        reactorHash.add("ProjectPy");
        reactorHash.add("ProjectR");
        reactorHash.add("ProjectReconnectServer");
        reactorHash.add("ReloadProjectProperties");
        reactorHash.add("RequestProject");
        reactorHash.add("UnlockProjects");
        reactorHash.add("ValidateProjectDependencies");
        reactorHash.add("ValidateUserProjectDependencies");
        reactorHash.add("PullProjectFolderFromCloud");
        reactorHash.add("PushProjectFolderToCloud");
    }

    private static void createEngineReactorHash(Set<String> reactorHash) {
        reactorHash.add("CreateModelEngine");
        reactorHash.add("CreatePythonFunctionEngine");
        reactorHash.add("CreateRestFunctionEngine");
        reactorHash.add("CreateStorageEngine");
        reactorHash.add("CreateVectorDatabaseEngine");
        reactorHash.add("CreateVenvEngine");
        reactorHash.add("ExecuteFunctionEngine");
        reactorHash.add("ExecuteReactorFunctionEngine");
        reactorHash.add("ExecuteStreamingFunctionEngine");
        reactorHash.add("GetFunctionEngineDefintion");
        reactorHash.add("EngineActivity");
        reactorHash.add("EngineInfo");
        reactorHash.add("EnginePy");
        reactorHash.add("MyEngineProject");
        reactorHash.add("MyEngines");
        reactorHash.add("MyDiscoverableEngines");
        reactorHash.add("DeleteEngine");
        reactorHash.add("ExportEngine");
        reactorHash.add("UploadEngine");
        reactorHash.add("CloseEngine");
        reactorHash.add("UnlockEngine");
        reactorHash.add("RequestEngine");
        reactorHash.add("CheckEngineName");
        reactorHash.add("GetEngineAssets");
        reactorHash.add("GetEngineAssetsBase64");
        reactorHash.add("GetEngineFiles");
        reactorHash.add("GetEngineMarkdown");
        reactorHash.add("GetEngineMetaValues");
        reactorHash.add("GetEngineMetadata");
        reactorHash.add("GetEngineMetakeyOptions");
        reactorHash.add("GetEngineSMSS");
        reactorHash.add("GetEngineUsage");
        reactorHash.add("GetEngineUsagePerProject");
        reactorHash.add("GetEngineUsagePerUser");
        reactorHash.add("GetEngineUserAccessRequest");
        reactorHash.add("LoadEngineMetadata");
        reactorHash.add("SetEngineMetadata");
        reactorHash.add("UpdateEngineAppLink");
        reactorHash.add("UpdateEngineFiles");
        reactorHash.add("PullEngineFromCloud");
        reactorHash.add("PushEngineToCloud");
        reactorHash.add("ReplaceInaccessibleEngines");
        reactorHash.add("RemoteEngineRun");
        reactorHash.add("RemoteModelShutdown");
        reactorHash.add("RemoteModelStart");
        reactorHash.add("MyRemoteModelsStatus");
        reactorHash.add("GetRemoteModelDeployConfigs");
        reactorHash.add("CopyEnginePermissions");
        reactorHash.add("VoteEngine");
        reactorHash.add("UnvoteEngine");
    }

    private static void createVectorDatabaseReactorHash(Set<String> reactorHash) {
        reactorHash.add("Vector");
        reactorHash.add("VectorDatabaseQuery");
        reactorHash.add("VectorAttachFileToSource");
        reactorHash.add("VectorFileDownload");
        reactorHash.add("ListAllRecordsInVectorDatabase");
        reactorHash.add("ListDocumentsInVectorDatabase");
        reactorHash.add("RemoveDocumentFromVectorDatabase");
        reactorHash.add("CreateVectorDatabaseEngine");
    }

    private static void createMCPReactorHash(Set<String> reactorHash) {
        reactorHash.add("InitMCP");
        reactorHash.add("MakeNotebookCellMCP");
        reactorHash.add("MakePixelMCP");
        reactorHash.add("MakePythonMCP");
        reactorHash.add("GetMCPInternalTools");
        reactorHash.add("GetMCPPrompts");
        reactorHash.add("GetMCPResources");
        reactorHash.add("GetMCPResourcesTemplates");
        reactorHash.add("GetMCPTools");
        reactorHash.add("RunMCPTool");
    }

    public static Map<String, Set<String>> getReactorHashByGroups() {
        Map<String, Set<String>> groupedReactors = new HashMap<>();

        groupedReactors.put("importMerge", getReactorsByGroupName("importMerge"));
        groupedReactors.put("utility", getReactorsByGroupName("utility"));
        groupedReactors.put("uploadUtils", getReactorsByGroupName("uploadUtils"));
        groupedReactors.put("excelValidation", getReactorsByGroupName("excelValidation"));
        groupedReactors.put("uploading", getReactorsByGroupName("uploading"));
        groupedReactors.put("graph", getReactorsByGroupName("graph"));
        groupedReactors.put("queryStruct", getReactorsByGroupName("queryStruct"));
        groupedReactors.put("databaseModification", getReactorsByGroupName("databaseModification"));
        groupedReactors.put("dataSource", getReactorsByGroupName("dataSource"));
        groupedReactors.put("frame", getReactorsByGroupName("frame"));
        groupedReactors.put("task", getReactorsByGroupName("task"));
        groupedReactors.put("taskOperations", getReactorsByGroupName("taskOperations"));
        groupedReactors.put("localMaster", getReactorsByGroupName("localMaster"));
        groupedReactors.put("owlMeta", getReactorsByGroupName("owlMeta"));
        groupedReactors.put("panel", getReactorsByGroupName("panel"));
        groupedReactors.put("insight", getReactorsByGroupName("insight"));
        groupedReactors.put("save", getReactorsByGroupName("save"));
        groupedReactors.put("dashboard", getReactorsByGroupName("dashboard"));
        groupedReactors.put("generalFrame", getReactorsByGroupName("generalFrame"));
        groupedReactors.put("algorithm", getReactorsByGroupName("algorithm"));
        groupedReactors.put("storage", getReactorsByGroupName("storage"));
        groupedReactors.put("git", getReactorsByGroupName("git"));
        groupedReactors.put("appMetadata", getReactorsByGroupName("appMetadata"));
        groupedReactors.put("cluster", getReactorsByGroupName("cluster"));
        groupedReactors.put("userSpace", getReactorsByGroupName("userSpace"));
        groupedReactors.put("scheduler", getReactorsByGroupName("scheduler"));
        groupedReactors.put("userTracking", getReactorsByGroupName("userTracking"));
        groupedReactors.put("recommendations", getReactorsByGroupName("recommendations"));
        groupedReactors.put("forms", getReactorsByGroupName("forms"));
        groupedReactors.put("legacyPlaysheet", getReactorsByGroupName("legacyPlaysheet"));
        groupedReactors.put("lsa", getReactorsByGroupName("lsa"));
        groupedReactors.put("generalCodeExecution", getReactorsByGroupName("generalCodeExecution"));
        groupedReactors.put("pixelRecipe", getReactorsByGroupName("pixelRecipe"));
        groupedReactors.put("webScrape", getReactorsByGroupName("webScrape"));
        groupedReactors.put("bitly", getReactorsByGroupName("bitly"));
        groupedReactors.put("date", getReactorsByGroupName("date"));
        groupedReactors.put("llmAi", getReactorsByGroupName("llmAi"));
        groupedReactors.put("admin", getReactorsByGroupName("admin"));
        groupedReactors.put("project", getReactorsByGroupName("project"));
        groupedReactors.put("engine", getReactorsByGroupName("engine"));
        groupedReactors.put("vectorDatabase", getReactorsByGroupName("vectorDatabase"));
        groupedReactors.put("mcp", getReactorsByGroupName("mcp"));
        return groupedReactors;
    }

    private static Set<String> getReactorsByGroupName(String groupName) {
        Set<String> reactorHash = new HashSet<>();

        switch (groupName.toLowerCase()) {
            case "importmerge":
                createImportMergeReactorHash(reactorHash);
                break;
            case "utility":
                createUtilyReactorHash(reactorHash);
                break;
            case "uploadutils":
                createUploadUtilsReactorHash(reactorHash);
                break;
            case "excelvalidation":
                createExcelDataValidationReactorHash(reactorHash);
                break;
            case "uploading":
                createUploadingReactorHash(reactorHash);
                break;
            case "graph":
                createGraphReactorHash(reactorHash);
                break;
            case "querystruct":
                createQueryStructReactorHash(reactorHash);
                break;
            case "databasemodification":
                createDatabaseModificationReactorHash(reactorHash);
                break;
            case "datasource":
                createDataSourceReactorHash(reactorHash);
                break;
            case "frame":
                createFrameReactorHash(reactorHash);
                break;
            case "task":
                createTaskReactorHash(reactorHash);
                break;
            case "taskoperations":
                createTaskOperationsReactorHash(reactorHash);
                break;
            case "localmaster":
                createLocalMasterReactorHash(reactorHash);
                break;
            case "owlmeta":
                createOwlMetaReactorHash(reactorHash);
                break;
            case "panel":
                createPanelReactorHash(reactorHash);
                break;
            case "insight":
                createInsightReactorHash(reactorHash);
                break;
            case "save":
                createSaveReactorHash(reactorHash);
                break;
            case "dashboard":
                createDashboardReactorHash(reactorHash);
                break;
            case "generalframe":
                createGeneralFrameReactorHash(reactorHash);
                break;
            case "algorithm":
                createAlgorithmReactorHash(reactorHash);
                break;
            case "storage":
                createStorageReactorHash(reactorHash);
                break;
            case "git":
                createGitReactorHash(reactorHash);
                break;
            case "appmetadata":
                createAppMetadataReactorHash(reactorHash);
                break;
            case "cluster":
                createClusterReactorHash(reactorHash);
                break;
            case "userspace":
                createUserSpaceReactorHash(reactorHash);
                break;
            case "scheduler":
                createSchedulerReactorHash(reactorHash);
                break;
            case "usertracking":
                createUserTrackingReactorHash(reactorHash);
                break;
            case "recommendations":
                createRecommendationsReactorHash(reactorHash);
                break;
            case "forms":
                createFormsReactorHash(reactorHash);
                break;
            case "legacyplaysheet":
                createLegacyPlaysheetReactorHash(reactorHash);
                break;
            case "lsa":
                createLSAReactorHash(reactorHash);
                break;
            case "generalcodeexecution":
                createGeneralCodeExecutionReactorHash(reactorHash);
                break;
            case "pixelrecipe":
                createPixelRecipeReactorHash(reactorHash);
                break;
            case "webscrape":
                createWebScrapeReactorHash(reactorHash);
                break;
            case "bitly":
                createBitlyReactorHash(reactorHash);
                break;
            case "date":
                createDateReactorHash(reactorHash);
                break;
            case "llmai":
                createLLMAIReactorHash(reactorHash);
                break;
            case "admin":
                createAdminReactorHash(reactorHash);
                break;
            case "project":
                createProjectReactorHash(reactorHash);
                break;
            case "engine":
                createEngineReactorHash(reactorHash);
                break;
            case "vectordatabase":
                createVectorDatabaseReactorHash(reactorHash);
                break;
            case "mcp":
                createMCPReactorHash(reactorHash);
                break;
            default:
                break;
        }

        return reactorHash;
    }
}
