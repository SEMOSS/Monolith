package prerna.web.services.util;

import java.util.Map;

import prerna.date.reactor.DateReactor;
import prerna.date.reactor.DayReactor;
import prerna.date.reactor.MonthReactor;
import prerna.date.reactor.WeekReactor;
import prerna.date.reactor.YearReactor;
import prerna.forms.UpdateFormReactor;
import prerna.io.connector.surveymonkey.SurveyMonkeyListSurveysReactor;
import prerna.poi.main.helper.excel.GetExcelFormReactor;
import prerna.query.querystruct.delete.DeleteReactor;
import prerna.query.querystruct.update.reactors.UpdateReactor;
import prerna.reactor.IReactor;
import prerna.reactor.algorithms.CreateNLPVizReactor;
import prerna.reactor.algorithms.NLPInstanceCacheReactor;
import prerna.reactor.algorithms.NLSQueryHelperReactor;
import prerna.reactor.algorithms.NaturalLanguageSearchReactor;
import prerna.reactor.algorithms.RAlgReactor;
import prerna.reactor.algorithms.RatioReactor;
import prerna.reactor.algorithms.RunAnomalyReactor;
import prerna.reactor.algorithms.RunClusteringReactor;
import prerna.reactor.algorithms.RunLOFReactor;
import prerna.reactor.algorithms.RunMatrixRegressionReactor;
import prerna.reactor.algorithms.RunMultiClusteringReactor;
import prerna.reactor.algorithms.RunNumericalCorrelationReactor;
import prerna.reactor.algorithms.RunOutlierReactor;
import prerna.reactor.algorithms.RunSimilarityReactor;
import prerna.reactor.algorithms.UpdateNLPHistoryReactor;
import prerna.reactor.algorithms.xray.GetCSVSchemaReactor;
import prerna.reactor.algorithms.xray.GetLocalSchemaReactor;
import prerna.reactor.algorithms.xray.GetXLSchemaReactor;
import prerna.reactor.algorithms.xray.GetXrayConfigFileReactor;
import prerna.reactor.algorithms.xray.GetXrayConfigListReactor;
import prerna.reactor.cluster.CleanUpDatabasesReactor;
import prerna.reactor.cluster.OpenDatabaseReactor;
import prerna.reactor.cluster.VersionReactor;
import prerna.reactor.database.DatabaseColumnUniqueReactor;
import prerna.reactor.database.metaeditor.GetOwlDescriptionsReactor;
import prerna.reactor.database.metaeditor.GetOwlDictionaryReactor;
import prerna.reactor.database.metaeditor.GetOwlLogicalNamesReactor;
import prerna.reactor.database.metaeditor.GetOwlMetamodelReactor;
import prerna.reactor.database.metaeditor.ReloadDatabaseOwlReactor;
import prerna.reactor.database.metaeditor.concepts.AddOwlConceptReactor;
import prerna.reactor.database.metaeditor.concepts.EditOwlConceptConceptualNameReactor;
import prerna.reactor.database.metaeditor.concepts.EditOwlConceptDataTypeReactor;
import prerna.reactor.database.metaeditor.concepts.RemoveOwlConceptReactor;
import prerna.reactor.database.metaeditor.meta.AddOwlDescriptionReactor;
import prerna.reactor.database.metaeditor.meta.AddOwlLogicalNamesReactor;
import prerna.reactor.database.metaeditor.meta.EditOwlDescriptionReactor;
import prerna.reactor.database.metaeditor.meta.EditOwlLogicalNamesReactor;
import prerna.reactor.database.metaeditor.meta.RemoveOwlDescriptionReactor;
import prerna.reactor.database.metaeditor.meta.RemoveOwlLogicalNamesReactor;
import prerna.reactor.database.metaeditor.properties.AddOwlPropertyReactor;
import prerna.reactor.database.metaeditor.properties.EditOwlPropertyConceptualNameReactor;
import prerna.reactor.database.metaeditor.properties.EditOwlPropertyDataTypeReactor;
import prerna.reactor.database.metaeditor.properties.RemoveOwlPropertyReactor;
import prerna.reactor.database.metaeditor.relationships.AddBulkOwlRelationshipsReactor;
import prerna.reactor.database.metaeditor.relationships.AddOwlRelationshipReactor;
import prerna.reactor.database.metaeditor.relationships.RemoveOwlRelationshipReactor;
import prerna.reactor.database.metaeditor.routines.FindDirectOwlRelationshipsReactor;
import prerna.reactor.database.metaeditor.routines.FindIndirectOwlRelationshipsReactor;
import prerna.reactor.database.metaeditor.routines.FindSemanticColumnOwlRelationshipsReactor;
import prerna.reactor.database.metaeditor.routines.FindSemanticInstanceOwlRelationshipsReactor;
import prerna.reactor.database.metaeditor.routines.PredictOwlDescriptionReactor;
import prerna.reactor.database.metaeditor.routines.PredictOwlLogicalNamesReactor;
import prerna.reactor.database.upload.CheckHeadersReactor;
import prerna.reactor.database.upload.ParseMetamodelReactor;
import prerna.reactor.database.upload.PredictDataTypesReactor;
import prerna.reactor.database.upload.PredictExcelDataTypesReactor;
import prerna.reactor.database.upload.PredictExcelRangeMetadataReactor;
import prerna.reactor.database.upload.PredictMetamodelReactor;
import prerna.reactor.database.upload.gremlin.external.CreateExternalDSEGraphDatabaseReactor;
import prerna.reactor.database.upload.gremlin.external.CreateExternalGraphDatabaseReactor;
import prerna.reactor.database.upload.gremlin.external.CreateJanusGraphDatabaseReactor;
import prerna.reactor.database.upload.gremlin.external.GetDSEGraphMetaModelReactor;
import prerna.reactor.database.upload.gremlin.external.GetDSEGraphPropertiesReactor;
import prerna.reactor.database.upload.gremlin.external.GetGraphMetaModelReactor;
import prerna.reactor.database.upload.gremlin.external.GetGraphPropertiesReactor;
import prerna.reactor.database.upload.gremlin.external.GetJanusGraphMetaModelReactor;
import prerna.reactor.database.upload.gremlin.external.GetJanusGraphPropertiesReactor;
import prerna.reactor.database.upload.gremlin.file.TinkerCsvUploadReactor;
import prerna.reactor.database.upload.rdbms.csv.RdbmsCsvUploadReactor;
import prerna.reactor.database.upload.rdbms.csv.RdbmsUploadTableDataReactor;
import prerna.reactor.database.upload.rdbms.excel.RdbmsLoaderSheetUploadReactor;
import prerna.reactor.database.upload.rdbms.excel.RdbmsUploadExcelDataReactor;
import prerna.reactor.database.upload.rdbms.external.ExternalJdbcSchemaReactor;
import prerna.reactor.database.upload.rdbms.external.ExternalJdbcTablesAndViewsReactor;
import prerna.reactor.database.upload.rdbms.external.RdbmsExternalUploadReactor;
import prerna.reactor.database.upload.rdf.RdfCsvUploadReactor;
import prerna.reactor.database.upload.rdf.RdfLoaderSheetUploadReactor;
import prerna.reactor.export.AsTaskReactor;
import prerna.reactor.export.CollectAllReactor;
import prerna.reactor.export.CollectGraphReactor;
import prerna.reactor.export.CollectReactor;
import prerna.reactor.export.DropBoxUploaderReactor;
import prerna.reactor.export.EmptyDataReactor;
import prerna.reactor.export.GoogleUploaderReactor;
import prerna.reactor.export.GrabScalarElementReactor;
import prerna.reactor.export.IterateReactor;
import prerna.reactor.export.OneDriveUploaderReactor;
import prerna.reactor.export.ToCsvReactor;
import prerna.reactor.export.ToDatabaseReactor;
import prerna.reactor.export.ToExcelReactor;
import prerna.reactor.export.ToLoaderSheetReactor;
import prerna.reactor.export.ToTsvReactor;
import prerna.reactor.export.ToTxtReactor;
import prerna.reactor.federation.FederationBestMatches;
import prerna.reactor.federation.FederationBlend;
import prerna.reactor.federation.FuzzyMatchesReactor;
import prerna.reactor.federation.FuzzyMergeReactor;
import prerna.reactor.frame.CreateFrameReactor;
import prerna.reactor.frame.CurrentFrameReactor;
import prerna.reactor.frame.FrameHeaderExistsReactor;
import prerna.reactor.frame.FrameHeadersReactor;
import prerna.reactor.frame.FrameTypeReactor;
import prerna.reactor.frame.HasDuplicatesReactor;
import prerna.reactor.frame.SetCurrentFrameReactor;
import prerna.reactor.frame.convert.ConvertReactor;
import prerna.reactor.frame.filter.AddFrameFilterReactor;
import prerna.reactor.frame.filter.DeleteFrameFilterReactor;
import prerna.reactor.frame.filter.GetFrameFiltersReactor;
import prerna.reactor.frame.filter.RemoveFrameFilterReactor;
import prerna.reactor.frame.filter.ReplaceFrameFilterReactor;
import prerna.reactor.frame.filter.SetFrameFilterReactor;
import prerna.reactor.frame.filter.UnfilterFrameReactor;
import prerna.reactor.frame.filtermodel.FrameFilterModelFilteredValuesReactor;
import prerna.reactor.frame.filtermodel.FrameFilterModelNumericRangeReactor;
import prerna.reactor.frame.filtermodel.FrameFilterModelReactor;
import prerna.reactor.frame.filtermodel.FrameFilterModelVisibleValuesReactor;
import prerna.reactor.frame.py.GenerateFrameFromPyVariableReactor;
import prerna.reactor.frame.r.GenerateFrameFromRVariableReactor;
import prerna.reactor.frame.r.GenerateH2FrameFromRVariableReactor;
import prerna.reactor.frame.r.SemanticBlendingReactor;
import prerna.reactor.frame.r.SemanticDescription;
import prerna.reactor.frame.r.analytics.RunAssociatedLearningReactor;
import prerna.reactor.frame.r.analytics.RunClassificationReactor;
import prerna.reactor.imports.ImportReactor;
import prerna.reactor.imports.MergeReactor;
import prerna.reactor.insights.ClearInsightReactor;
import prerna.reactor.insights.CurrentVariablesReactor;
import prerna.reactor.insights.DropInsightReactor;
import prerna.reactor.insights.InsightHandleReactor;
import prerna.reactor.insights.LoadInsightReactor;
import prerna.reactor.insights.OpenEmptyInsightReactor;
import prerna.reactor.insights.OpenInsightReactor;
import prerna.reactor.insights.RetrieveInsightOrnamentReactor;
import prerna.reactor.insights.SetInsightOrnamentReactor;
import prerna.reactor.insights.copy.CopyInsightReactor;
import prerna.reactor.insights.dashboard.DashboardInsightConfigReactor;
import prerna.reactor.insights.dashboard.ReloadInsightReactor;
import prerna.reactor.insights.recipemanagement.GetCurrentRecipeReactor;
import prerna.reactor.insights.recipemanagement.InsightRecipeReactor;
import prerna.reactor.insights.recipemanagement.RetrieveInsightPipelineReactor;
import prerna.reactor.insights.save.DeleteInsightCacheReactor;
import prerna.reactor.insights.save.DeleteInsightReactor;
import prerna.reactor.insights.save.SaveInsightReactor;
import prerna.reactor.insights.save.SetInsightCacheableReactor;
import prerna.reactor.insights.save.SetInsightNameReactor;
import prerna.reactor.insights.save.UpdateInsightImageReactor;
import prerna.reactor.insights.save.UpdateInsightReactor;
import prerna.reactor.masterdatabase.AllConceptualNamesReactor;
import prerna.reactor.masterdatabase.CLPModelReactor;
import prerna.reactor.masterdatabase.GetConceptPropertiesReactor;
import prerna.reactor.masterdatabase.GetDatabaseConceptsReactor;
import prerna.reactor.masterdatabase.GetDatabaseConnectionsReactor;
import prerna.reactor.masterdatabase.GetDatabaseListReactor;
import prerna.reactor.masterdatabase.GetDatabaseMetamodelReactor;
import prerna.reactor.masterdatabase.GetDatabaseTableStructureReactor;
import prerna.reactor.masterdatabase.GetPhysicalToLogicalMapping;
import prerna.reactor.masterdatabase.GetPhysicalToPhysicalMapping;
import prerna.reactor.masterdatabase.GetSpecificConceptPropertiesReactor;
import prerna.reactor.masterdatabase.GetTraversalOptionsReactor;
import prerna.reactor.masterdatabase.QueryTranslatorReactor;
import prerna.reactor.masterdatabase.SyncDatabaseWithLocalMasterReactor;
import prerna.reactor.panel.AddPanelConfigReactor;
import prerna.reactor.panel.AddPanelIfAbsentReactor;
import prerna.reactor.panel.AddPanelReactor;
import prerna.reactor.panel.CachedPanelCloneReactor;
import prerna.reactor.panel.CachedPanelReactor;
import prerna.reactor.panel.CloneReactor;
import prerna.reactor.panel.ClosePanelReactor;
import prerna.reactor.panel.GetPanelIdReactor;
import prerna.reactor.panel.InsightPanelIds;
import prerna.reactor.panel.PanelExistsReactor;
import prerna.reactor.panel.PanelReactor;
import prerna.reactor.panel.SetPanelLabelReactor;
import prerna.reactor.panel.SetPanelPositionReactor;
import prerna.reactor.panel.SetPanelViewReactor;
import prerna.reactor.panel.comments.AddPanelCommentReactor;
import prerna.reactor.panel.comments.RemovePanelCommentReactor;
import prerna.reactor.panel.comments.RetrievePanelCommentReactor;
import prerna.reactor.panel.comments.UpdatePanelCommentReactor;
import prerna.reactor.panel.events.AddPanelEventsReactor;
import prerna.reactor.panel.events.RemovePanelEventsReactor;
import prerna.reactor.panel.events.ResetPanelEventsReactor;
import prerna.reactor.panel.events.RetrievePanelEventsReactor;
import prerna.reactor.panel.external.OpenTabReactor;
import prerna.reactor.panel.filter.AddPanelFilterReactor;
import prerna.reactor.panel.filter.SetPanelFilterReactor;
import prerna.reactor.panel.filter.UnfilterPanelReactor;
import prerna.reactor.panel.ornaments.AddPanelOrnamentsReactor;
import prerna.reactor.panel.ornaments.RemovePanelOrnamentsReactor;
import prerna.reactor.panel.ornaments.ResetPanelOrnamentsReactor;
import prerna.reactor.panel.ornaments.RetrievePanelOrnamentsReactor;
import prerna.reactor.panel.rules.AddPanelColorByValueReactor;
import prerna.reactor.panel.rules.GetPanelColorByValueReactor;
import prerna.reactor.panel.rules.RemovePanelColorByValueReactor;
import prerna.reactor.panel.rules.RetrievePanelColorByValueReactor;
import prerna.reactor.panel.sort.AddPanelSortReactor;
import prerna.reactor.panel.sort.SetPanelSortReactor;
import prerna.reactor.panel.sort.UnsortPanelReactor;
import prerna.reactor.planner.GraphPlanReactor;
import prerna.reactor.project.AddDefaultInsightsReactor;
import prerna.reactor.qs.DistinctReactor;
import prerna.reactor.qs.ExecQueryReactor;
import prerna.reactor.qs.GroupReactor;
import prerna.reactor.qs.ImplicitFilterOverrideReactor;
import prerna.reactor.qs.InsertReactor;
import prerna.reactor.qs.JoinReactor;
import prerna.reactor.qs.LimitReactor;
import prerna.reactor.qs.OffsetReactor;
import prerna.reactor.qs.QueryAllReactor;
import prerna.reactor.qs.QueryReactor;
import prerna.reactor.qs.SortReactor;
import prerna.reactor.qs.WithReactor;
import prerna.reactor.qs.filter.FilterReactor;
import prerna.reactor.qs.filter.HavingReactor;
import prerna.reactor.qs.filter.RegexFilterReactor;
import prerna.reactor.qs.selectors.AverageReactor;
import prerna.reactor.qs.selectors.CountReactor;
import prerna.reactor.qs.selectors.GroupConcatReactor;
import prerna.reactor.qs.selectors.LowerReactor;
import prerna.reactor.qs.selectors.MaxReactor;
import prerna.reactor.qs.selectors.MedianReactor;
import prerna.reactor.qs.selectors.MinReactor;
import prerna.reactor.qs.selectors.PColReactor;
import prerna.reactor.qs.selectors.PSelectReactor;
import prerna.reactor.qs.selectors.SelectReactor;
import prerna.reactor.qs.selectors.SelectTableReactor;
import prerna.reactor.qs.selectors.StandardDeviationReactor;
import prerna.reactor.qs.selectors.SumReactor;
import prerna.reactor.qs.selectors.UniqueAverageReactor;
import prerna.reactor.qs.selectors.UniqueCountReactor;
import prerna.reactor.qs.selectors.UniqueGroupConcatReactor;
import prerna.reactor.qs.selectors.UniqueSumReactor;
import prerna.reactor.qs.source.APIReactor;
import prerna.reactor.qs.source.AuditDatabaseReactor;
import prerna.reactor.qs.source.DatabaseReactor;
import prerna.reactor.qs.source.DirectJdbcConnectionReactor;
import prerna.reactor.qs.source.DropBoxFileRetrieverReactor;
import prerna.reactor.qs.source.DropBoxListFilesReactor;
import prerna.reactor.qs.source.FileReadReactor;
import prerna.reactor.qs.source.FrameReactor;
import prerna.reactor.qs.source.GoogleFileRetrieverReactor;
import prerna.reactor.qs.source.GoogleListFilesReactor;
import prerna.reactor.qs.source.JdbcSourceReactor;
import prerna.reactor.qs.source.OneDriveFileRetrieverReactor;
import prerna.reactor.qs.source.OneDriveListFilesReactor;
import prerna.reactor.qs.source.SharePointDriveSelectorReactor;
import prerna.reactor.qs.source.SharePointFileRetrieverReactor;
import prerna.reactor.qs.source.SharePointListFilesReactor;
import prerna.reactor.qs.source.SharePointSiteSelectorReactor;
import prerna.reactor.qs.source.SharePointWebDavPullReactor;
import prerna.reactor.qs.source.URLSourceReactor;
import prerna.reactor.runtime.JavaReactor;
import prerna.reactor.scheduler.ListAllJobsReactor;
import prerna.reactor.scheduler.PauseJobTriggerReactor;
import prerna.reactor.scheduler.ResumeJobTriggerReactor;
import prerna.reactor.scheduler.ScheduleJobReactor;
import prerna.reactor.security.DatabaseInfoReactor;
import prerna.reactor.security.DatabaseUsersReactor;
import prerna.reactor.security.GetInsightsReactor;
import prerna.reactor.security.MyDatabasesReactor;
import prerna.reactor.task.AutoTaskOptionsReactor;
import prerna.reactor.task.CollectMetaReactor;
import prerna.reactor.task.FormatReactor;
import prerna.reactor.task.RefreshPanelTaskReactor;
import prerna.reactor.task.RemoveTaskReactor;
import prerna.reactor.task.ResetTaskReactor;
import prerna.reactor.task.TaskOptionsReactor;
import prerna.reactor.task.TaskReactor;
import prerna.reactor.task.lambda.map.function.ApplyFormattingReactor;
import prerna.reactor.task.modifiers.CodeLambdaReactor;
import prerna.reactor.task.modifiers.FilterLambdaReactor;
import prerna.reactor.task.modifiers.FlatMapLambdaReactor;
import prerna.reactor.task.modifiers.ToNumericTypeReactor;
import prerna.reactor.task.modifiers.ToUrlTypeReactor;
import prerna.reactor.task.modifiers.TransposeRowsReactor;
import prerna.reactor.tax.RetrieveValue;
import prerna.reactor.tax.StoreValue;
import prerna.reactor.test.LSASpaceColumnLearnedReactor;
import prerna.reactor.test.RunLSILearnedReactor;
import prerna.reactor.utils.AddOperationAliasReactor;
import prerna.reactor.utils.BDelReactor;
import prerna.reactor.utils.BQReactor;
import prerna.reactor.utils.BackupDatabaseReactor;
import prerna.reactor.utils.BaddReactor;
import prerna.reactor.utils.BupdReactor;
import prerna.reactor.utils.CheckRPackagesReactor;
import prerna.reactor.utils.CheckRecommendOptimizationReactor;
import prerna.reactor.utils.DatabaseProfileReactor;
import prerna.reactor.utils.DeleteDatabaseReactor;
import prerna.reactor.utils.ExportDatabaseReactor;
import prerna.reactor.utils.ExternalDatabaseProfileReactor;
import prerna.reactor.utils.GetNumTableReactor;
import prerna.reactor.utils.GetRequestReactor;
import prerna.reactor.utils.GetTableHeader;
import prerna.reactor.utils.GetUserInfoReactor;
import prerna.reactor.utils.HelpReactor;
import prerna.reactor.utils.ImageCaptureReactor;
import prerna.reactor.utils.PostRequestReactor;
import prerna.reactor.utils.RemoveVariableReactor;
import prerna.reactor.utils.SendEmailReactor;
import prerna.reactor.utils.VariableExistsReactor;
import prerna.reactor.workflow.GetInsightDatasourcesReactor;
import prerna.reactor.workflow.GetOptimizedRecipeReactor;
import prerna.reactor.workflow.ModifyInsightDatasourceReactor;
import prerna.reactor.workspace.DeleteUserAssetReactor;
import prerna.reactor.workspace.MoveUserAssetReactor;
import prerna.reactor.workspace.NewDirReactor;
import prerna.reactor.workspace.UploadUserFileReactor;
import prerna.reactor.workspace.UserDirReactor;
import prerna.util.git.reactors.AddAppCollaborator;
import prerna.util.git.reactors.CopyAppRepo;
import prerna.util.git.reactors.DeleteAppRepo;
import prerna.util.git.reactors.DropAppRepo;
import prerna.util.git.reactors.GitStatusReactor;
import prerna.util.git.reactors.InitAppRepo;
import prerna.util.git.reactors.IsGit;
import prerna.util.git.reactors.ListAppCollaborators;
import prerna.util.git.reactors.ListAppRemotes;
import prerna.util.git.reactors.ListUserApps;
import prerna.util.git.reactors.LoginReactor;
import prerna.util.git.reactors.RemoveAppCollaborator;
import prerna.util.git.reactors.SearchAppCollaborator;
import prerna.util.git.reactors.SyncApp;
import prerna.util.git.reactors.SyncAppFiles;
import prerna.util.git.reactors.SyncAppFilesO;
import prerna.util.git.reactors.SyncAppOReactor;
import prerna.util.usertracking.reactors.ExtractDatabaseMetaReactor;
import prerna.util.usertracking.reactors.UpdateQueryDataReactor;
import prerna.util.usertracking.reactors.UpdateSemanticDataReactor;
import prerna.util.usertracking.reactors.WidgetTReactor;
import prerna.util.usertracking.reactors.recommendations.DatabaseRecommendationsReactor;
import prerna.util.usertracking.reactors.recommendations.GetDatabasesByDescriptionReactor;
import prerna.util.usertracking.reactors.recommendations.VizRecommendationsReactor;

public final class ReactorResourceGroups {

    private static void createImportMergeReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		// This method is intentionally left blank.
		// Its purpose is to ensure that all reactor classes are loaded into the JVM,
		// which in turn populates the ReactorFactory.reactorHash via static
		// initializers.
		reactorHash.put("Import", ImportReactor.class);
		reactorHash.put("Merge", MergeReactor.class);

	}

	private static void createUtilyReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("AddOperationAlias", AddOperationAliasReactor.class);
		reactorHash.put("VariableExists", VariableExistsReactor.class);
		reactorHash.put("RemoveVariable", RemoveVariableReactor.class);
		reactorHash.put("SendEmail", SendEmailReactor.class);
		reactorHash.put("BackupDatabase", BackupDatabaseReactor.class);
		reactorHash.put("ExportDatabase", ExportDatabaseReactor.class);
		reactorHash.put("DeleteDatabase", DeleteDatabaseReactor.class);
		reactorHash.put("ImageCapture", ImageCaptureReactor.class);
		reactorHash.put("Help", HelpReactor.class);
		reactorHash.put("help", HelpReactor.class);
		reactorHash.put("DatabaseProfile", DatabaseProfileReactor.class);
		reactorHash.put("DatabaseColumnUnique", DatabaseColumnUniqueReactor.class);
		reactorHash.put("ExternalDatabaseProfile", ExternalDatabaseProfileReactor.class);
		reactorHash.put("GetRequest", GetRequestReactor.class);
		reactorHash.put("PostRequest", PostRequestReactor.class);
		reactorHash.put("CheckRPackages", CheckRPackagesReactor.class);
		reactorHash.put("CheckRecommendOptimization", CheckRecommendOptimizationReactor.class);
		reactorHash.put("PredictExcelRangeMetadata", PredictExcelRangeMetadataReactor.class);
		reactorHash.put("DeleteInsightCache", DeleteInsightCacheReactor.class);
		reactorHash.put("WidgetT", WidgetTReactor.class);
		reactorHash.put("GetUserInfo", GetUserInfoReactor.class);
	}

	private static void createUploadUtilsReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("CheckHeaders", CheckHeadersReactor.class);
		reactorHash.put("PredictDataTypes", PredictDataTypesReactor.class);
		reactorHash.put("PredictExcelDataTypes", PredictExcelDataTypesReactor.class);
		reactorHash.put("PredictMetamodel", PredictMetamodelReactor.class);
		reactorHash.put("ParseMetamodel", ParseMetamodelReactor.class);
		reactorHash.put("ExtractAppMeta", ExtractDatabaseMetaReactor.class);
		reactorHash.put("NLPInstanceCache", NLPInstanceCacheReactor.class);
	}

	private static void createExcelDataValidationReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("GetExcelForm", GetExcelFormReactor.class);

	}

	private static void createUploadingReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("ExternalJdbcSchema", ExternalJdbcSchemaReactor.class);
		reactorHash.put("ExternalJdbcTablesAndViews", ExternalJdbcTablesAndViewsReactor.class);
		reactorHash.put("RdbmsUploadTableData", RdbmsUploadTableDataReactor.class);
		reactorHash.put("RdbmsUploadExcelData", RdbmsUploadExcelDataReactor.class);
		reactorHash.put("RdbmsExternalUpload", RdbmsExternalUploadReactor.class);
		reactorHash.put("RdbmsCsvUpload", RdbmsCsvUploadReactor.class);
		reactorHash.put("RdbmsLoaderSheetUpload", RdbmsLoaderSheetUploadReactor.class);
		reactorHash.put("RdfCsvUpload", RdfCsvUploadReactor.class);
		reactorHash.put("RdfLoaderSheetUpload", RdfLoaderSheetUploadReactor.class);
		reactorHash.put("TinkerCsvUpload", TinkerCsvUploadReactor.class);
	}

	private static void createGraphReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("GetGraphProperties", GetGraphPropertiesReactor.class);
		reactorHash.put("GetGraphMetaModel", GetGraphMetaModelReactor.class);
		reactorHash.put("CreateExternalGraphDatabase", CreateExternalGraphDatabaseReactor.class);
		// datastax graph reactors
		reactorHash.put("GetDSEGraphProperties", GetDSEGraphPropertiesReactor.class);
		reactorHash.put("GetDSEGraphMetaModel", GetDSEGraphMetaModelReactor.class);
		reactorHash.put("CreateExternalDSEGraphDatabase", CreateExternalDSEGraphDatabaseReactor.class);
		// janus graph reactors
		reactorHash.put("GetJanusGraphProperties", GetJanusGraphPropertiesReactor.class);
		reactorHash.put("GetJanusGraphMetaModel", GetJanusGraphMetaModelReactor.class);
		reactorHash.put("CreateJanusGraphDatabase", CreateJanusGraphDatabaseReactor.class);
	}

	private static void createQueryStructReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("With", WithReactor.class);
		reactorHash.put("Select", SelectReactor.class);
		reactorHash.put("SelectTable", SelectTableReactor.class);
		reactorHash.put("PSelect", PSelectReactor.class);
		reactorHash.put("PCol", PColReactor.class);
		reactorHash.put("Mean", AverageReactor.class);
		reactorHash.put("UniqueAverage", UniqueAverageReactor.class);
		reactorHash.put("UniqueMean", UniqueAverageReactor.class);
		reactorHash.put("Sum", SumReactor.class);
		reactorHash.put("UniqueSum", UniqueSumReactor.class);
		reactorHash.put("Max", MaxReactor.class);
		reactorHash.put("Min", MinReactor.class);
		reactorHash.put("Median", MedianReactor.class);
		reactorHash.put("StandardDeviation", StandardDeviationReactor.class);
		reactorHash.put("Count", CountReactor.class);
		reactorHash.put("UniqueCount", UniqueCountReactor.class);
		reactorHash.put("GroupConcat", GroupConcatReactor.class);
		reactorHash.put("UniqueGroupConcat", UniqueGroupConcatReactor.class);
		reactorHash.put("Lower", LowerReactor.class);
		reactorHash.put("Group", GroupReactor.class);
		reactorHash.put("GroupBy", GroupReactor.class);
		reactorHash.put("Sort", SortReactor.class);
		reactorHash.put("Order", SortReactor.class);
		reactorHash.put("Limit", LimitReactor.class);
		reactorHash.put("Offset", OffsetReactor.class);
		reactorHash.put("Join", JoinReactor.class);
		reactorHash.put("Filter", FilterReactor.class);
		reactorHash.put("RegexFilter", RegexFilterReactor.class);
		reactorHash.put("Having", HavingReactor.class);
		reactorHash.put("Query", QueryReactor.class);
		reactorHash.put("Distinct", DistinctReactor.class);
		reactorHash.put("ImplicitFilterOverride", ImplicitFilterOverrideReactor.class);
		reactorHash.put("QueryAll", QueryAllReactor.class);

	}
//Start of AI Reactor Hash
	private static void createDatabaseModificationReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("Insert", InsertReactor.class);
		reactorHash.put("Delete", DeleteReactor.class);
		reactorHash.put("Update", UpdateReactor.class);
		reactorHash.put("ExecQuery", ExecQueryReactor.class);
	}

	private static void createDataSourceReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("Database", DatabaseReactor.class);
		reactorHash.put("AuditDatabase", AuditDatabaseReactor.class);
		reactorHash.put("API", APIReactor.class);
		reactorHash.put("FileRead", FileReadReactor.class);
		reactorHash.put("JdbcSource", JdbcSourceReactor.class);
		reactorHash.put("DirectJDBCConnection", DirectJdbcConnectionReactor.class);
		reactorHash.put("URLSource", URLSourceReactor.class);
		// drop box
		reactorHash.put("DropBoxUploader", DropBoxUploaderReactor.class);
		reactorHash.put("DropBoxListFiles", DropBoxListFilesReactor.class);
		reactorHash.put("DropBoxFileRetriever", DropBoxFileRetrieverReactor.class);
		// one drive
		reactorHash.put("OneDriveUploader", OneDriveUploaderReactor.class);
		reactorHash.put("OneDriveListFiles", OneDriveListFilesReactor.class);
		reactorHash.put("OneDriveFileRetriever", OneDriveFileRetrieverReactor.class);
		// google
		reactorHash.put("GoogleUploader", GoogleUploaderReactor.class);
		reactorHash.put("GoogleListFiles", GoogleListFilesReactor.class);
		reactorHash.put("GoogleFileRetriever", GoogleFileRetrieverReactor.class);
		// share point
		reactorHash.put("SharePointListFiles", SharePointListFilesReactor.class);
		reactorHash.put("SharePointFileRetriever", SharePointFileRetrieverReactor.class);
		reactorHash.put("SharePointSiteSelector", SharePointSiteSelectorReactor.class);
		reactorHash.put("SharePointDriveSelector", SharePointDriveSelectorReactor.class);
		reactorHash.put("SharePointWebDavPull", SharePointWebDavPullReactor.class);
		// survey monkey
		reactorHash.put("SurveyMonkeyListSurveys", SurveyMonkeyListSurveysReactor.class);
		reactorHash.put("NaturalLanguageSearch", NaturalLanguageSearchReactor.class);
	}

	private static void createFrameReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("Frame", FrameReactor.class);
		reactorHash.put("CreateFrame", CreateFrameReactor.class);
		reactorHash.put("FrameType", FrameTypeReactor.class);
		reactorHash.put("Convert", ConvertReactor.class);
		reactorHash.put("GenerateFrameFromRVariable", GenerateFrameFromRVariableReactor.class);
		reactorHash.put("GenerateFrameFromPyVariable", GenerateFrameFromPyVariableReactor.class);
		reactorHash.put("GenerateH2FrameFromRVariable", GenerateH2FrameFromRVariableReactor.class);
	}

	private static void createTaskReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("Iterate", IterateReactor.class);
		reactorHash.put("Task", TaskReactor.class);
		reactorHash.put("ResetTask", ResetTaskReactor.class);
		reactorHash.put("ResetAll", RefreshPanelTaskReactor.class);
		reactorHash.put("RemoveTask", RemoveTaskReactor.class);
		reactorHash.put("Collect", CollectReactor.class);
		reactorHash.put("CollectAll", CollectAllReactor.class);
		reactorHash.put("CollectGraph", CollectGraphReactor.class);
		reactorHash.put("GrabScalarElement", GrabScalarElementReactor.class);
		reactorHash.put("AsTask", AsTaskReactor.class);
		reactorHash.put("EmptyData", EmptyDataReactor.class);
		reactorHash.put("CollectMeta", CollectMetaReactor.class);
		reactorHash.put("Format", FormatReactor.class);
		reactorHash.put("TaskOptions", TaskOptionsReactor.class);
		reactorHash.put("AutoTaskOptions", AutoTaskOptionsReactor.class);
		reactorHash.put("ToCsv", ToCsvReactor.class);
		reactorHash.put("ToTsv", ToTsvReactor.class);
		reactorHash.put("ToTxt", ToTxtReactor.class);
		reactorHash.put("ToExcel", ToExcelReactor.class);
		reactorHash.put("ToDatabase", ToDatabaseReactor.class);
		reactorHash.put("ToLoaderSheet", ToLoaderSheetReactor.class);
	}

	private static void createTaskOperationsReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("CodeLambda", CodeLambdaReactor.class);
		reactorHash.put("FlatMapLambda", FlatMapLambdaReactor.class);
		reactorHash.put("FilterLambda", FilterLambdaReactor.class);
		reactorHash.put("ToNumericType", ToNumericTypeReactor.class);
		reactorHash.put("ToUrlType", ToUrlTypeReactor.class);
		reactorHash.put("TransposeRows", TransposeRowsReactor.class);
		reactorHash.put("ApplyFormatting", ApplyFormattingReactor.class);
	}

	private static void createLocalMasterReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("GetDatabaseList", GetDatabaseListReactor.class);
		reactorHash.put("GetDatabaseConcepts", GetDatabaseConceptsReactor.class);
		reactorHash.put("GetTraversalOptions", GetTraversalOptionsReactor.class);
		reactorHash.put("GetDatabaseMetamodel", GetDatabaseMetamodelReactor.class);
		reactorHash.put("GetConceptProperties", GetConceptPropertiesReactor.class);
		// NEW FEDERATE
		reactorHash.put("GetDatabaseConnections", GetDatabaseConnectionsReactor.class);
		reactorHash.put("GetDatabaseTableStructure", GetDatabaseTableStructureReactor.class);
		reactorHash.put("GetSpecificConceptProperties", GetSpecificConceptPropertiesReactor.class);
		reactorHash.put("FuzzyMatches", FuzzyMatchesReactor.class);
		reactorHash.put("FuzzyMerge", FuzzyMergeReactor.class);
		// deprecated
		reactorHash.put("FederationBlend", FederationBlend.class);
		reactorHash.put("FederationBestMatches", FederationBestMatches.class);
	}

	private static void createOwlMetaReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("ReloadDatabaseOwl", ReloadDatabaseOwlReactor.class);
		reactorHash.put("GetOwlMetamodel", GetOwlMetamodelReactor.class);
		reactorHash.put("GetOwlDictionary", GetOwlDictionaryReactor.class);
		// owl concepts
		reactorHash.put("AddOwlConcept", AddOwlConceptReactor.class);
		reactorHash.put("RemoveOwlConcept", RemoveOwlConceptReactor.class);
		// owl properties
		reactorHash.put("AddOwlProperty", AddOwlPropertyReactor.class);
		reactorHash.put("RemoveOwlProperty", RemoveOwlPropertyReactor.class);
		// owl relationships
		reactorHash.put("AddOwlRelationship", AddOwlRelationshipReactor.class);
		reactorHash.put("AddBulkOwlRelationships", AddBulkOwlRelationshipsReactor.class);
		reactorHash.put("RemoveOwlRelationship", RemoveOwlRelationshipReactor.class);
		// conceptual names
		reactorHash.put("EditOwlConceptConceptualName", EditOwlConceptConceptualNameReactor.class);
		reactorHash.put("EditOwlPropertyConceptualName", EditOwlPropertyConceptualNameReactor.class);
		// data types
		reactorHash.put("EditOwlConceptDataType", EditOwlConceptDataTypeReactor.class);
		reactorHash.put("EditOwlPropertyDataType", EditOwlPropertyDataTypeReactor.class);
		// logical names
		reactorHash.put("AddOwlLogicalNames", AddOwlLogicalNamesReactor.class);
		reactorHash.put("EditOwlLogicalNames", EditOwlLogicalNamesReactor.class);
		reactorHash.put("RemoveOwlLogicalNames", RemoveOwlLogicalNamesReactor.class);
		reactorHash.put("GetOwlLogicalNames", GetOwlLogicalNamesReactor.class);
		reactorHash.put("PredictOwlLogicalNames", PredictOwlLogicalNamesReactor.class);
		// descriptions
		reactorHash.put("AddOwlDescription", AddOwlDescriptionReactor.class);
		reactorHash.put("EditOwlDescription", EditOwlDescriptionReactor.class);
		reactorHash.put("RemoveOwlDescription", RemoveOwlDescriptionReactor.class);
		reactorHash.put("GetOwlDescriptions", GetOwlDescriptionsReactor.class);
		reactorHash.put("PredictOwlDescription", PredictOwlDescriptionReactor.class);
		// routines to predict owl information
		reactorHash.put("FindDirectOwlRelationships", FindDirectOwlRelationshipsReactor.class);
		reactorHash.put("FindIndirectOwlRelationships", FindIndirectOwlRelationshipsReactor.class);
		reactorHash.put("FindSemanticColumnOwlRelationships", FindSemanticColumnOwlRelationshipsReactor.class);
		reactorHash.put("FindSemanticInstanceOwlRelationships", FindSemanticInstanceOwlRelationshipsReactor.class);
		reactorHash.put("SyncDatabaseWithLocalMaster", SyncDatabaseWithLocalMasterReactor.class);
		reactorHash.put("QueryTranslator", QueryTranslatorReactor.class);
		reactorHash.put("AllConceptualNames", AllConceptualNamesReactor.class);
		reactorHash.put("CLPModel", CLPModelReactor.class);
	}

	private static void createPanelReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("InsightPanelIds", InsightPanelIds.class);
		reactorHash.put("Panel", PanelReactor.class);
		reactorHash.put("CachedPanel", CachedPanelReactor.class);
		reactorHash.put("CachedPanelClone", CachedPanelCloneReactor.class);
		reactorHash.put("AddPanel", AddPanelReactor.class);
		reactorHash.put("AddPanelIfAbsent", AddPanelIfAbsentReactor.class);
		reactorHash.put("GetPanelId", GetPanelIdReactor.class);
		reactorHash.put("ClosePanel", ClosePanelReactor.class);
		reactorHash.put("PanelExists", PanelExistsReactor.class);
		reactorHash.put("Clone", CloneReactor.class);
		reactorHash.put("SetPanelLabel", SetPanelLabelReactor.class);
		reactorHash.put("SetPanelView", SetPanelViewReactor.class);
		// panel filters
		reactorHash.put("AddPanelFilter", AddPanelFilterReactor.class);
		reactorHash.put("SetPanelFilter", SetPanelFilterReactor.class);
		reactorHash.put("UnfilterPanel", UnfilterPanelReactor.class);
		// panel sort
		reactorHash.put("AddPanelSort", AddPanelSortReactor.class);
		reactorHash.put("SetPanelSort", SetPanelSortReactor.class);
		reactorHash.put("RemovePanelSort", UnsortPanelReactor.class);
		reactorHash.put("UnsortPanel", UnsortPanelReactor.class);
		// panel comments
		reactorHash.put("AddPanelComment", AddPanelCommentReactor.class);
		reactorHash.put("UpdatePanelComment", UpdatePanelCommentReactor.class);
		reactorHash.put("RemovePanelComment", RemovePanelCommentReactor.class);
		reactorHash.put("RetrievePanelComment", RetrievePanelCommentReactor.class);
		// panel ornaments
		reactorHash.put("AddPanelOrnaments", AddPanelOrnamentsReactor.class);
		reactorHash.put("RemovePanelOrnaments", RemovePanelOrnamentsReactor.class);
		reactorHash.put("ResetPanelOrnaments", ResetPanelOrnamentsReactor.class);
		reactorHash.put("RetrievePanelOrnaments", RetrievePanelOrnamentsReactor.class);
		// panel configuration
		reactorHash.put("AddPanelConfig", AddPanelConfigReactor.class);
		// panel events
		reactorHash.put("AddPanelEvents", AddPanelEventsReactor.class);
		reactorHash.put("RemovePanelEvents", RemovePanelEventsReactor.class);
		reactorHash.put("ResetPanelEvents", ResetPanelEventsReactor.class);
		reactorHash.put("RetrievePanelEvents", RetrievePanelEventsReactor.class);
		// panel position
		reactorHash.put("SetPanelPosition", SetPanelPositionReactor.class);
		// panel color by value
		reactorHash.put("AddPanelColorByValue", AddPanelColorByValueReactor.class);
		reactorHash.put("RetrievePanelColorByValue", RetrievePanelColorByValueReactor.class);
		reactorHash.put("RemovePanelColorByValue", RemovePanelColorByValueReactor.class);
		reactorHash.put("GetPanelColorByValue", GetPanelColorByValueReactor.class);
		// new tab in browser
		reactorHash.put("OpenTab", OpenTabReactor.class);
	}

	private static void createInsightReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("InsightRecipe", InsightRecipeReactor.class);
		reactorHash.put("CurrentVariables", CurrentVariablesReactor.class);
		reactorHash.put("OpenInsight", OpenInsightReactor.class);
		reactorHash.put("LoadInsight", LoadInsightReactor.class);
		reactorHash.put("ReloadInsight", ReloadInsightReactor.class);
		reactorHash.put("CopyInsight", CopyInsightReactor.class);
		reactorHash.put("OpenEmptyInsight", OpenEmptyInsightReactor.class);
		reactorHash.put("DropInsight", DropInsightReactor.class);
		reactorHash.put("ClearInsight", ClearInsightReactor.class);
		reactorHash.put("InsightHandle", InsightHandleReactor.class);
		reactorHash.put("SetInsightOrnament", SetInsightOrnamentReactor.class);
		reactorHash.put("RetrieveInsightOrnament", RetrieveInsightOrnamentReactor.class);
		reactorHash.put("UpdateInsightImage", UpdateInsightImageReactor.class);
		reactorHash.put("GetCurrentRecipe", GetCurrentRecipeReactor.class);
		reactorHash.put("RetrieveInsightPipeline", RetrieveInsightPipelineReactor.class);
	}

	private static void createSaveReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("SaveInsight", SaveInsightReactor.class);
		reactorHash.put("UpdateInsight", UpdateInsightReactor.class);
		reactorHash.put("DeleteInsight", DeleteInsightReactor.class);
		reactorHash.put("SetInsightName", SetInsightNameReactor.class);
		reactorHash.put("SetInsightCacheable", SetInsightCacheableReactor.class);
	}

	private static void createDashboardReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("DashboardInsightConfig", DashboardInsightConfigReactor.class);
	}

	private static void createGeneralFrameReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("FrameHeaders", FrameHeadersReactor.class);
		reactorHash.put("FrameHeaderExists", FrameHeaderExistsReactor.class);
		reactorHash.put("AddFrameFilter", AddFrameFilterReactor.class);
		reactorHash.put("GetFrameFilters", GetFrameFiltersReactor.class);
		reactorHash.put("SetFrameFilter", SetFrameFilterReactor.class);
		reactorHash.put("RemoveFrameFilter", RemoveFrameFilterReactor.class);
		reactorHash.put("ReplaceFrameFilter", ReplaceFrameFilterReactor.class);
		reactorHash.put("DeleteFrameFilter", DeleteFrameFilterReactor.class);
		reactorHash.put("UnfilterFrame", UnfilterFrameReactor.class);
		reactorHash.put("HasDuplicates", HasDuplicatesReactor.class);
		reactorHash.put("CurrentFrame", CurrentFrameReactor.class);
		reactorHash.put("SetCurrentFrame", SetCurrentFrameReactor.class);
		// filter model
		reactorHash.put("FrameFilterModel", FrameFilterModelReactor.class);
		reactorHash.put("FrameFilterModelFilteredValues", FrameFilterModelFilteredValuesReactor.class);
		reactorHash.put("FrameFilterModelVisibleValues", FrameFilterModelVisibleValuesReactor.class);
		reactorHash.put("FrameFilterModelNumericRange", FrameFilterModelNumericRangeReactor.class);
	}

	private static void createAlgorithmReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("rAlg", RAlgReactor.class);
		reactorHash.put("RunClustering", RunClusteringReactor.class);
		reactorHash.put("RunMultiClustering", RunMultiClusteringReactor.class);
		reactorHash.put("RunLOF", RunLOFReactor.class);
		reactorHash.put("RunSimilarity", RunSimilarityReactor.class);
		reactorHash.put("RunOutlier", RunOutlierReactor.class);
		reactorHash.put("Ratio", RatioReactor.class);
		reactorHash.put("RunAnomaly", RunAnomalyReactor.class);
		// X-Ray reactors
		reactorHash.put("GetXrayConfigList", GetXrayConfigListReactor.class);
		reactorHash.put("GetXrayConfigFile", GetXrayConfigFileReactor.class);
		reactorHash.put("GetLocalSchema", GetLocalSchemaReactor.class);
		reactorHash.put("GetXLSchema", GetXLSchemaReactor.class);
		reactorHash.put("GetCSVSchema", GetCSVSchemaReactor.class);
		reactorHash.put("SemanticBlending", SemanticBlendingReactor.class);
		reactorHash.put("SemanticDescription", SemanticDescription.class);
		// similar reactors to x-ray
		reactorHash.put("GetPhysicalToLogicalMapping", GetPhysicalToLogicalMapping.class);
		reactorHash.put("GetPhysicalToPhysicalMapping", GetPhysicalToPhysicalMapping.class);
		// these algorithms return viz data to the FE
		reactorHash.put("RunNumericalCorrelation", RunNumericalCorrelationReactor.class);
		reactorHash.put("RunMatrixRegression", RunMatrixRegressionReactor.class);
		reactorHash.put("RunClassification", RunClassificationReactor.class);
		reactorHash.put("RunAssociatedLearning", RunAssociatedLearningReactor.class);
	}

	private static void createStorageReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("StoreValue", StoreValue.class);
		reactorHash.put("RetrieveValue", RetrieveValue.class);
		reactorHash.put("GraphPlan", GraphPlanReactor.class);
	}

	private static void createGitReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("InitAppRepo", InitAppRepo.class);
		reactorHash.put("AddAppCollaborator", AddAppCollaborator.class);
		reactorHash.put("RemoveAppCollaborator", RemoveAppCollaborator.class);
		reactorHash.put("SearchAppCollaborator", SearchAppCollaborator.class);
		reactorHash.put("ListAppCollaborators", ListAppCollaborators.class);
		reactorHash.put("CopyAppRepo", CopyAppRepo.class);
		reactorHash.put("DeleteAppRepo", DeleteAppRepo.class);
		reactorHash.put("DropAppRepo", DropAppRepo.class);
		reactorHash.put("SyncApp", SyncApp.class);
		reactorHash.put("SyncAppFiles", SyncAppFiles.class);
		reactorHash.put("ListAppRemotes", ListAppRemotes.class);
		reactorHash.put("ListUserApps", ListUserApps.class);
		reactorHash.put("IsGit", IsGit.class);
		reactorHash.put("Login", LoginReactor.class);
		reactorHash.put("GitStatus", GitStatusReactor.class);
		reactorHash.put("GitVersion", prerna.util.git.reactors.GitVersion.class);
		reactorHash.put("CreateAsset", prerna.util.git.reactors.CreateAssetReactor.class);
		reactorHash.put("UpdateAsset", prerna.util.git.reactors.UpdateAssetReactor.class);
		reactorHash.put("DeleteAsset", prerna.util.git.reactors.DeleteAssetReactor.class);
		reactorHash.put("SyncAppO", SyncAppOReactor.class);
		reactorHash.put("SyncAppFilesO", SyncAppFilesO.class);
	}

	private static void createAppMetadataReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("MyDatabases", MyDatabasesReactor.class);
		reactorHash.put("DatabaseInfo", DatabaseInfoReactor.class);
		reactorHash.put("DatabaseUsersReactor", DatabaseUsersReactor.class);
		reactorHash.put("GetAppInsights", GetInsightsReactor.class);
		reactorHash.put("GetInsights", GetInsightsReactor.class);
		reactorHash.put("AddDefaultInsights", AddDefaultInsightsReactor.class);
	}

	private static void createClusterReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("OpenDatabase", OpenDatabaseReactor.class);
		reactorHash.put("CleanUpDatabases", CleanUpDatabasesReactor.class);
		reactorHash.put("Version", VersionReactor.class);
	}

	private static void createUserSpaceReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("UploadUserFile", UploadUserFileReactor.class);
		reactorHash.put("UserDir", UserDirReactor.class);
		reactorHash.put("DeleteUserAsset", DeleteUserAssetReactor.class);
		reactorHash.put("NewDir", NewDirReactor.class);
		reactorHash.put("MoveUserAsset", MoveUserAssetReactor.class);
	}

	private static void createSchedulerReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("ScheduleJob", ScheduleJobReactor.class);
		reactorHash.put("PauseJobTrigger", PauseJobTriggerReactor.class);
		reactorHash.put("ListAllJobs", ListAllJobsReactor.class);
		reactorHash.put("ResumeJobTrigger", ResumeJobTriggerReactor.class);
	}

	private static void createUserTrackingReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("UpdateSemanticData", UpdateSemanticDataReactor.class);
		reactorHash.put("UpdateQueryData", UpdateQueryDataReactor.class);
	}

	private static void createRecommendationsReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("DatabaseRecommendations", DatabaseRecommendationsReactor.class);
		reactorHash.put("VizRecommendations", VizRecommendationsReactor.class);
		reactorHash.put("PredictViz", CreateNLPVizReactor.class);
		reactorHash.put("GetDatabasesByDescription", GetDatabasesByDescriptionReactor.class);
		reactorHash.put("UpdateNLPHistory", UpdateNLPHistoryReactor.class);
		reactorHash.put("NLSQueryHelper", NLSQueryHelperReactor.class);
	}

	private static void createFormsReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("UpdateForm", UpdateFormReactor.class);
	}

	private static void createLegacyPlaysheetReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("RunPlaysheetMethod", prerna.reactor.legacy.playsheets.RunPlaysheetMethodReactor.class);
		reactorHash.put("RunPlaysheet", prerna.reactor.legacy.playsheets.RunPlaysheetReactor.class);
		reactorHash.put("GetPlaysheetParams", prerna.reactor.legacy.playsheets.GetPlaysheetParamsReactor.class);
	}

	private static void createLSAReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("LSASpaceColumnLearned", LSASpaceColumnLearnedReactor.class);
		reactorHash.put("RunLSILearned", RunLSILearnedReactor.class);
	}

	private static void createGeneralCodeExecutionReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("Java", JavaReactor.class);
	}

	private static void createPixelRecipeReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("GetInsightDatasources", GetInsightDatasourcesReactor.class);
		reactorHash.put("ModifyInsightDatasource", ModifyInsightDatasourceReactor.class);
		reactorHash.put("GetOptimizedRecipe", GetOptimizedRecipeReactor.class);
	}

	private static void createWebScrapeReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("GetTableHeader", GetTableHeader.class);
		reactorHash.put("GetNumTable", GetNumTableReactor.class);
	}

	private static void createBitlyReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("badd", BaddReactor.class);
		reactorHash.put("bupd", BupdReactor.class);
		reactorHash.put("bdel", BDelReactor.class);
		reactorHash.put("bq", BQReactor.class);
	}

	private static void createDateReactorHash(Map<String, Class<? extends IReactor>> reactorHash) {
		reactorHash.put("DATE", DateReactor.class);
		reactorHash.put("DAY", DayReactor.class);
		reactorHash.put("WEEK", WeekReactor.class);
		reactorHash.put("MONTH", MonthReactor.class);
		reactorHash.put("YEAR", YearReactor.class);
	}


}
