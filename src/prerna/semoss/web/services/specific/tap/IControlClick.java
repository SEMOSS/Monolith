package prerna.semoss.web.services.specific.tap;

import prerna.engine.api.IEngine;
import prerna.ui.components.api.IPlaySheet;

public interface IControlClick {
	void setPlaySheet(IPlaySheet playSheet);
	
	void setEngine(IEngine engine);
}
