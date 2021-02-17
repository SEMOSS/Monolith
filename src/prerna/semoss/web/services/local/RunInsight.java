package prerna.semoss.web.services.local;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.stream.JsonWriter;

import prerna.algorithm.api.ITableDataFrame;
import prerna.om.Insight;
import prerna.om.InsightPanel;
import prerna.om.InsightSheet;
import prerna.om.ThreadStore;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.PixelUtility;
import prerna.sablecc2.om.PixelDataType;
import prerna.sablecc2.om.PixelOperationType;
import prerna.sablecc2.om.VarStore;
import prerna.sablecc2.om.nounmeta.NounMetadata;
import prerna.sablecc2.reactor.job.JobReactor;
import prerna.util.gson.InsightPanelAdapter;
import prerna.util.gson.InsightSheetAdapter;
import prerna.util.insight.InsightUtility;
import prerna.web.requests.OverrideParametersServletRequest;

public class RunInsight {

	private static final Logger logger = LogManager.getLogger(RunInsight.class);
	
	private Insight in = null;
	private boolean drop = false;
	
	public RunInsight(Insight in) {
		this.in = in;
	}

	public void dropInsight(boolean drop) {
		this.drop = drop;
	}
	
	@POST
	@Path("/getTableData")
	@Produces("application/json")
	public Response getInsightData(@Context HttpServletRequest request) {
		String pixel = "QueryAll()|Collect(-1);FrameHeaders();";
		return runPixel(request, pixel);
	}
	
	@POST
	@Path("/getInsightState")
	@Produces("application/json")
	public Response recreateInsightState(@Context HttpServletRequest request) {
		List<String> recipe = PixelUtility.getCachedInsightRecipe(in);
		
		Insight rerunInsight = new Insight();
		rerunInsight.setVarStore(in.getVarStore());
		rerunInsight.setUser(in.getUser());
		InsightUtility.transferDefaultVars(in, rerunInsight);
		
		// set in thread
		ThreadStore.setInsightId(in.getInsightId());
		ThreadStore.setSessionId(in.getVarStore().get(JobReactor.SESSION_KEY).getValue() + "");
		ThreadStore.setUser(in.getUser());
		
		try {
			// add a copy of all the insight sheets
			Map<String, InsightSheet> sheets = in.getInsightSheets();
			for(String sheetId : sheets.keySet()) {
				InsightSheetAdapter adapter = new InsightSheetAdapter();
				StringWriter writer = new StringWriter();
				JsonWriter jWriter = new JsonWriter(writer);
				adapter.write(jWriter, sheets.get(sheetId));
				String sheetStr = writer.toString();
				InsightSheet sheetClone = adapter.fromJson(sheetStr);
				rerunInsight.addNewInsightSheet(sheetClone);
			}
			
			// add a copy of all the insight panels
			Map<String, InsightPanel> panels = in.getInsightPanels();
			for(String panelId : panels.keySet()) {
				InsightPanelAdapter adapter = new InsightPanelAdapter();
				StringWriter writer = new StringWriter();
				JsonWriter jWriter = new JsonWriter(writer);
				adapter.write(jWriter, panels.get(panelId));
				String panelStr = writer.toString();
				InsightPanel panelClone = adapter.fromJson(panelStr);
				rerunInsight.addNewInsightPanel(panelClone);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		PixelRunner pixelRunner = new PixelRunner();
		// add all the frame headers to the payload first
		try {
			VarStore vStore = in.getVarStore();
			List<String> keys = vStore.getFrameKeys();
			for(String k : keys) {
				NounMetadata noun = vStore.get(k);
				PixelDataType type = noun.getNounType();
				if(type == PixelDataType.FRAME) {
					try {
						ITableDataFrame frame = (ITableDataFrame) noun.getValue();
						pixelRunner.addResult("CACHED_FRAME_HEADERS", 
								new NounMetadata(frame.getFrameHeadersObject(), PixelDataType.CUSTOM_DATA_STRUCTURE, 
										PixelOperationType.FRAME_HEADERS), true);
					} catch(Exception e) {
						e.printStackTrace();
						// ignore
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		// now rerun the recipe and append to the runner
		in.runPixel(pixelRunner, recipe);
		
		return Response.status(200).entity(PixelStreamUtility.collectPixelData(pixelRunner))
				.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
				.header("Pragma", "no-cache")
				.build();
	}
	
//	private OptimizeRecipeTranslation getOptimizedRecipe(List<String> recipe) {
//		OptimizeRecipeTranslation translation = new OptimizeRecipeTranslation();
//		for (int i = 0; i < recipe.size(); i++) {
//			String expression = recipe.get(i);
//			// fill in the encodedToOriginal with map for the current expression
//			expression = PixelPreProcessor.preProcessPixel(expression.trim(), translation.encodingList, translation.encodedToOriginal);
//			try {
//				Parser p = new Parser(
//						new Lexer(
//								new PushbackReader(
//										new InputStreamReader(
//												new ByteArrayInputStream(expression.getBytes("UTF-8")), "UTF-8"), expression.length())));
//				// parsing the pixel - this process also determines if expression is syntactically correct
//				Start tree = p.parse();
//				// apply the translation
//				// when we apply the translation, we will change encoded expressions back to their original form
//				tree.apply(translation);
//				// reset translation.encodedToOriginal for each expression
//				translation.encodedToOriginal = new HashMap<String, String>();
//			} catch (ParserException | LexerException | IOException e) {
//				logger.error(Constants.STACKTRACE, e);
//			}
//		}
//		return translation;
//	}
	
	/**
	 * Utility method to execute the pixel on the insight
	 * @param request
	 * @param pixel
	 * @return
	 */
	private Response runPixel(@Context HttpServletRequest request, String pixel) {
		if(this.drop) {
			if(pixel.endsWith(";")) {
				pixel = pixel + "DropInsight();";
			} else {
				pixel = pixel + ";DropInsight();";
			}
		}
		NameServer ns = new NameServer();
		OverrideParametersServletRequest requestWrapper = new OverrideParametersServletRequest(request);
		Map<String, String> paramMap = new HashMap<String, String>();
		paramMap.put("insightId", in.getInsightId());
		paramMap.put("expression", pixel);
		requestWrapper.setParameters(paramMap);
		return ns.runPixelSync(requestWrapper);
	}
}
