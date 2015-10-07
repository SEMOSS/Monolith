package prerna.web.services.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import prerna.algorithm.api.ITableDataFrame;
import prerna.ds.ITableWebAdapter;
import prerna.ds.TableDataFrameStore;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.BasicProcessingPlaySheet;
import prerna.util.QuestionPlaySheetStore;

public final class TableDataFrameUtilities {

	private TableDataFrameUtilities() {
		
	}
	
	public static void filterData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap) {
		mainTree.unfilter();
		//boolean unfiltered = false;
		for(String concept: filterValuesArrMap.keySet()) {

			List<Object> filterValuesArr = filterValuesArrMap.get(concept);
			if(mainTree.isNumeric(concept)) {
				List<Object> values = new ArrayList<Object>(filterValuesArr.size());
				for(Object o: filterValuesArr) {
					try {
						values.add(Double.parseDouble(o.toString()));
					} catch(Exception e) {
						values.add(o);
					}
				}
				filterValuesArr = values;
			}
			if(filterValuesArr.isEmpty()) {
				mainTree.filter(concept, Arrays.asList(mainTree.getUniqueValues(concept)));
				return;
			}

//			//if filterValuesArr not a subset of superSet, then unfilter
//			Object[] superSet = mainTree.getUniqueValues(concept);
//
//			int n = filterValuesArr.size();
//			int m = superSet.length;
//
//			if(m < n) {
//				mainTree.unfilter();
//				//unfiltered = true;
//			} else {
//				Comparator<Object> comparator = new Comparator<Object>() {
//					public int compare(Object o1, Object o2) {
//						return o1.toString().compareTo(o2.toString());
//					}
//				};
//
//				//check if filterValuesArr is a subset of superSet
//				Arrays.sort(superSet, comparator);
//				Collections.sort(filterValuesArr, comparator);
//
//				int i = 0;
//				int j = 0;
//				while(i < n && j < m) {
//					int compareTo = superSet[i].toString().compareToIgnoreCase(filterValuesArr.get(i).toString());
//					if(compareTo < 0) {
//						j++;
//					} else if(compareTo == 0) {
//						j++; i++;
//					} else if(compareTo > 0) {
//						mainTree.unfilter();
//						//unfiltered = true;
//						break;
//					}
//				}
//			}

//			List<Object> setDiff = new ArrayList<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
			Set<Object> totalSet = new HashSet<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
			for(Object o : filterValuesArr) {
				totalSet.remove(o);
			}
//			setDiff.removeAll(filterValuesArr);
			mainTree.filter(concept, new ArrayList<Object>(totalSet));
		}
	}
	
	public static void unfilterData() {
		
	}
	
	public static ITableDataFrame getTable(String tableID, String questionID) {
		ITableDataFrame table = null;
		
		try {
			if(tableID != null) {
				table = TableDataFrameStore.getInstance().get(tableID);
			} else if(questionID != null) {
				IPlaySheet origPS = (IPlaySheet) QuestionPlaySheetStore.getInstance().get(questionID);
				if(origPS instanceof BasicProcessingPlaySheet) {
					table = ((BasicProcessingPlaySheet) origPS).getDataFrame();
				}
			}
		} catch (Exception e) {
			return null;
		}

		return table;
	}
	
	public static List<HashMap<String, Object>> getTableData(ITableDataFrame table) {
		return ITableWebAdapter.getData(table);
	}
	
	public static Map<String, Boolean> checkRelationships() {
		//use this to import hasDuplicates code
		
		return null;
	}
	
	public static Map<String, Object> createColumnNamesForColumnGrouping(String columnHeader, Map<String, Object> functionMap) {
		
		for(String key : functionMap.keySet()) {
			
			Map<String, String> map = (Map<String, String>)functionMap.get(key);
			String name = map.get("name");
			String function = map.get("math");
			if(!name.equals(columnHeader)) {
				String newName = name+"_"+function+"_on_"+columnHeader;
				map.put("calcName", newName);
			}
		}
		
		return functionMap;
	}
	
	public static Map<String, Object>  createColumnNamesForColumnGrouping(String[] columnHeaders, Map<String, Object> functionMap) {
		String columnHeader = "";
		for(String c : columnHeaders) {
			columnHeader = columnHeader + c +"_and_";
		}
		
		columnHeader.substring(0, columnHeader.length() - 5);
		
		for(String key : functionMap.keySet()) {
			
			Map<String, String> map = (Map<String, String>)functionMap.get(key);
			String name = map.get("name");
			String function = map.get("math");
			
			String newName = name+"_"+function+"_on_"+columnHeader;
			map.put("calcName", newName);
			
		}
		
		return functionMap;
	}
}
