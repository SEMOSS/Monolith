package prerna.semoss.web.services.local;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.security.PermitAll;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONObject;

import prerna.reactor.IReactor;
import prerna.reactor.ReactorFactory;
import prerna.reactor.algorithms.NLPInstanceCacheReactor;
import prerna.reactor.database.DatabaseColumnUniqueReactor;
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
import prerna.reactor.imports.ImportReactor;
import prerna.reactor.imports.MergeReactor;
import prerna.reactor.insights.save.DeleteInsightCacheReactor;
import prerna.reactor.qs.DistinctReactor;
import prerna.reactor.qs.GroupReactor;
import prerna.reactor.qs.ImplicitFilterOverrideReactor;
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
import prerna.reactor.utils.AddOperationAliasReactor;
import prerna.reactor.utils.BackupDatabaseReactor;
import prerna.reactor.utils.CheckRPackagesReactor;
import prerna.reactor.utils.CheckRecommendOptimizationReactor;
import prerna.reactor.utils.DatabaseProfileReactor;
import prerna.reactor.utils.DeleteDatabaseReactor;
import prerna.reactor.utils.ExportDatabaseReactor;
import prerna.reactor.utils.ExternalDatabaseProfileReactor;
import prerna.reactor.utils.GetRequestReactor;
import prerna.reactor.utils.GetUserInfoReactor;
import prerna.reactor.utils.HelpReactor;
import prerna.reactor.utils.ImageCaptureReactor;
import prerna.reactor.utils.PostRequestReactor;
import prerna.reactor.utils.RemoveVariableReactor;
import prerna.reactor.utils.SendEmailReactor;
import prerna.reactor.utils.VariableExistsReactor;
import prerna.util.usertracking.reactors.ExtractDatabaseMetaReactor;
import prerna.util.usertracking.reactors.WidgetTReactor;
import prerna.web.services.util.WebUtility;
import prerna.algorithm.api.ITableDataFrame;
import prerna.date.reactor.DateReactor;
import prerna.date.reactor.DayReactor;
import prerna.date.reactor.MonthReactor;
import prerna.date.reactor.WeekReactor;
import prerna.date.reactor.YearReactor;
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
import prerna.om.Insight;
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
import prerna.poi.main.helper.excel.GetExcelFormReactor;
import prerna.reactor.project.AddDefaultInsightsReactor;
import prerna.reactor.qs.ExecQueryReactor;
import prerna.reactor.qs.InsertReactor;
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
import prerna.query.querystruct.delete.DeleteReactor;
import prerna.query.querystruct.update.reactors.UpdateReactor;
import prerna.reactor.algorithms.CreateNLPVizReactor;
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
import prerna.reactor.export.AsTaskReactor;
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
import prerna.reactor.utils.BDelReactor;
import prerna.reactor.utils.BQReactor;
import prerna.reactor.utils.BaddReactor;
import prerna.reactor.utils.BupdReactor;
import prerna.reactor.utils.GetNumTableReactor;
import prerna.reactor.utils.GetTableHeader;
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
import prerna.util.usertracking.reactors.UpdateQueryDataReactor;
import prerna.util.usertracking.reactors.UpdateSemanticDataReactor;
import prerna.util.usertracking.reactors.recommendations.DatabaseRecommendationsReactor;
import prerna.util.usertracking.reactors.recommendations.GetDatabasesByDescriptionReactor;
import prerna.util.usertracking.reactors.recommendations.VizRecommendationsReactor;
import prerna.forms.UpdateFormReactor;
import prerna.io.connector.surveymonkey.SurveyMonkeyListSurveysReactor;
import prerna.reactor.planner.GraphPlanReactor;

/**
 * REST resource to expose metadata about available Reactors.
 * Returns each reactor's name, description, required keys, optional keys, and
 * usage.
 */
@Path("/engine/reactors")
@PermitAll
public class ReactorResource {

	private static final Logger log = LogManager.getLogger(ReactorResource.class);

	@GET
	@Path("usageOnly")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactorsWithUsageOnly() {

		List<ReactorDTO> reactorList = getAllGeneralReactorNames().stream().map(r -> {
			return getReactorByName(r);
		}).filter(Objects::nonNull)
				.filter(reactor -> reactor.getUsage() != null && !reactor.getUsage().isBlank())
				.map(ReactorResource::mapReactor).collect(Collectors.toList());
		return WebUtility.getResponse(reactorList, 200);
	}

	@GET
	@Path("all")
	@Produces(MediaType.APPLICATION_JSON)
	public Response listReactors() {

		List<ReactorDTO> reactorList = getAllGeneralReactorNames().stream().map(r -> {
			return getReactorByName(r);
		}).filter(Objects::nonNull)
				.map(ReactorResource::mapReactor).collect(Collectors.toList());
		return WebUtility.getResponse(reactorList, 200);
	}

	private static IReactor getReactorByName(String name) {
		Insight i = null;
		IReactor pr = null;
		ITableDataFrame tdb = null;
		try {
			return ReactorFactory.getReactor(i, name, pr, tdb);
		} catch (Exception e) {
			log.warn("Failed to load reactor: {}", name, e);
			return null;
		}
	}

	private Set<String> getAllGeneralReactorNames() {
		return ReactorFactory.reactorHash.keySet();
	}

	
	private static ReactorDTO mapReactor(IReactor reactor) {
		// ReactorDTO dto = new
		String reactorName = reactor.getName();
		String description = null;
		List<String> requiredKeys = new ArrayList<>();
		Set<String> allKeys = new HashSet<>();
		String usage = reactor.getUsage();
		try {

			JSONObject tool = reactor.asMcpTool();
			if (tool != null) {
				description = tool.optString("description", null);
				if (tool.has("inputSchema")) {
					JSONObject inputSchema = tool.getJSONObject("inputSchema");
					if (inputSchema.has("required")) {
						for (Object o : inputSchema.getJSONArray("required")) {
							String key = String.valueOf(o).trim();
							if (!key.isBlank() && !requiredKeys.contains(key)) {
								requiredKeys.add(key);
							}
						}
					}
					if (inputSchema.has("properties")) {
						JSONObject props = inputSchema.getJSONObject("properties");
						for (String key : props.keySet()) {
							allKeys.add(key);
						}
					}
				}
				String reactorUsage = reactor.getUsage();
				if (reactorUsage != null && !reactorUsage.isBlank()) {
					usage = reactorUsage;
				}
			} else {
				log.debug("Null tool metadata for reactor {}", reactorName);
			}
		} catch (Exception ee) {
			log.debug("Could not derive MCP metadata for reactor {}", reactorName, ee);
		}
		return new ReactorDTO.Builder()
				.setName(reactorName)
				.setDescription(description)
				.setRequiredKeys(requiredKeys)
				.setOptionalKeys(allKeys.stream()
						.filter(Objects::nonNull)
						.map(String::trim)
						.filter(key -> !key.isBlank() && !requiredKeys.contains(key))
						.collect(Collectors.toList()))
				.setUsage(usage)
				.build();
	}

	// DTO class to hold reactor metadata
	static class ReactorDTO {
		public String name;
		public String description;
		public List<String> requiredKeys;
		public List<String> optionalKeys;
		public String usage;

		public ReactorDTO(String name, String description, List<String> requiredKeys, List<String> optionalKeys,
				String usage) {
			this.name = name;
			this.description = description;
			this.requiredKeys = requiredKeys;
			this.optionalKeys = optionalKeys;
			this.usage = usage;
		}

		// Generate builder pattern
		public static class Builder {
			private String name;
			private String description;
			private List<String> requiredKeys = new ArrayList<>();
			private List<String> optionalKeys = new ArrayList<>();
			private String usage;

			public Builder setName(String name) {
				this.name = name;
				return this;
			}

			public Builder setDescription(String description) {
				this.description = description;
				return this;
			}

			public Builder setRequiredKeys(List<String> requiredKeys) {
				this.requiredKeys = requiredKeys;
				return this;
			}

			public Builder setOptionalKeys(List<String> optionalKeys) {
				this.optionalKeys = optionalKeys;
				return this;
			}

			public Builder setUsage(String usage) {
				this.usage = usage;
				return this;
			}

			public ReactorDTO build() {
				return new ReactorDTO(name, description, requiredKeys, optionalKeys, usage);
			}
		}
	}

}
