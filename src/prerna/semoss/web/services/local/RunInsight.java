package prerna.semoss.web.services.local;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
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

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.google.gson.stream.JsonWriter;

import prerna.om.Insight;
import prerna.om.InsightPanel;
import prerna.om.InsightSheet;
import prerna.sablecc2.PixelPreProcessor;
import prerna.sablecc2.PixelRunner;
import prerna.sablecc2.PixelStreamUtility;
import prerna.sablecc2.lexer.Lexer;
import prerna.sablecc2.lexer.LexerException;
import prerna.sablecc2.node.Start;
import prerna.sablecc2.parser.Parser;
import prerna.sablecc2.parser.ParserException;
import prerna.sablecc2.translations.OptimizeRecipeTranslation;
import prerna.util.Constants;
import prerna.util.gson.InsightPanelAdapter;
import prerna.util.gson.InsightSheetAdapter;
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
		List<String> recipe = in.getPixelRecipe();
		
		OptimizeRecipeTranslation opTrans = getOptimizedRecipe(recipe);
		Insight rerunInsight = new Insight();
		rerunInsight.setVarStore(in.getVarStore());
		rerunInsight.setUser(in.getUser());
		
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
		
		PixelRunner pixelRunner = rerunInsight.runPixel(opTrans.getCachedPixelRecipeSteps());
		return Response.status(200).entity(PixelStreamUtility.collectPixelData(pixelRunner))
				.header("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0, post-check=0, pre-check=0")
				.header("Pragma", "no-cache")
				.build();
	}
	
	private OptimizeRecipeTranslation getOptimizedRecipe(List<String> recipe) {
		OptimizeRecipeTranslation translation = new OptimizeRecipeTranslation();
		for (int i = 0; i < recipe.size(); i++) {
			String expression = recipe.get(i);
			// fill in the encodedToOriginal with map for the current expression
			expression = PixelPreProcessor.preProcessPixel(expression.trim(), translation.encodingList, translation.encodedToOriginal);
			try {
				Parser p = new Parser(
						new Lexer(
								new PushbackReader(
										new InputStreamReader(
												new ByteArrayInputStream(expression.getBytes("UTF-8")), "UTF-8"), expression.length())));
				// parsing the pixel - this process also determines if expression is syntactically correct
				Start tree = p.parse();
				// apply the translation
				// when we apply the translation, we will change encoded expressions back to their original form
				tree.apply(translation);
				// reset translation.encodedToOriginal for each expression
				translation.encodedToOriginal = new HashMap<String, String>();
			} catch (ParserException | LexerException | IOException e) {
				logger.error(Constants.STACKTRACE, e);
			}
		}
		return translation;
	}
	
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
