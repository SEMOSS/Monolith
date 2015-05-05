package prerna.semoss.web.services.specific.tap;

import prerna.engine.api.IEngine;
import prerna.ui.components.api.IPlaySheet;

public abstract class AbstractControlClick implements IControlClick {
	
	IEngine engine;
	IPlaySheet playsheet;	

	@Override
	public void setPlaySheet(IPlaySheet playSheet) {
		this.playsheet = playSheet;		
	}

	@Override
	public void setEngine(IEngine engine) {
		this.engine = engine;		
	}
	
}