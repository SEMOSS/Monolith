package prerna.web.services.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.algorithm.api.ITableDataFrame;
import prerna.ds.TableDataFrameStore;
import prerna.ds.TableDataFrameWebAdapter;
import prerna.ui.components.api.IPlaySheet;
import prerna.ui.components.playsheets.BasicProcessingPlaySheet;
import prerna.util.QuestionPlaySheetStore;

public final class TableDataFrameUtilities {

	private static final Logger LOGGER = LogManager.getLogger(TableDataFrameUtilities.class.getName());
	
	private HashMap<String, HashSet<String>> touchedColumns = new HashMap<>();
	
	private TableDataFrameUtilities() {
		
	}
	
//	public static void filterData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap) {
////		mainTree.unfilter();
//		//boolean unfiltered = false;
//		Set<String> keySet = filterValuesArrMap.keySet();
//		String[] columnHeaders = mainTree.getColumnHeaders();
//		String[] concepts = keySet.toArray(new String[keySet.size()]);
//		for(String column : columnHeaders) {
//			if(!ArrayUtilityMethods.arrayContainsValue(concepts, column)) {
//				mainTree.unfilter(column);
//			}
//		}
//		
//		for(String concept: keySet) {
//
//			List<Object> filterValuesArr = filterValuesArrMap.get(concept);
//			if(mainTree.isNumeric(concept)) {
//				List<Object> values = new ArrayList<Object>(filterValuesArr.size());
//				for(Object o: filterValuesArr) {
//					try {
//						values.add(Double.parseDouble(o.toString()));
//					} catch(Exception e) {
//						values.add(o);
//					}
//				}
//				filterValuesArr = values;
//			}
//			if(filterValuesArr.isEmpty()) {
//				mainTree.filter(concept, Arrays.asList(mainTree.getUniqueValues(concept)));
//				return;
//			}
//
////			//if filterValuesArr not a subset of superSet, then unfilter
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
//
////			List<Object> setDiff = new ArrayList<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
//			Set<Object> totalSet = new HashSet<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
//			for(Object o : filterValuesArr) {
//				totalSet.remove(o);
//			}
////			setDiff.removeAll(filterValuesArr);
//			mainTree.filter(concept, new ArrayList<Object>(totalSet));
//		}
//	}
	
	public static void filterData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap) {

		LOGGER.info("Filtering on table");
		long startTime = System.currentTimeMillis();
		
		String[] columnHeaders = mainTree.getColumnHeaders();
		
		Map<String, Object[]> storedValues = new HashMap<String, Object[]>();
		for(String column: columnHeaders) {
			storedValues.put(column.toUpperCase(), mainTree.getUniqueValues(column));
		}
		
		Map<String, List<Object>> map = new HashMap<String, List<Object>>();
		for(String concept : filterValuesArrMap.keySet()) {
			map.put(concept.toUpperCase(), filterValuesArrMap.get(concept));
		}
		//need to find the which column is different from previous, then filter only that column
		//when first different column is found, call filterColumn on that column

		for(String columnHeader : columnHeaders) {
			columnHeader = columnHeader.toUpperCase();
			Object[] storedValuesArr = storedValues.get(columnHeader);
			if(map.containsKey(columnHeader)) {
				List<Object> filterValuesArr = map.get(columnHeader);
				if(!equals(filterValuesArr, storedValuesArr)) {
					filterColumn(mainTree, columnHeader, filterValuesArr);
				}
			} 
			else {
				int totalSize = mainTree.getUniqueRawValues(columnHeader).length + mainTree.getFilteredUniqueRawValues(columnHeader).length;
				if(totalSize != storedValuesArr.length) {
					mainTree.unfilter();
				}
			}
		}
		
		LOGGER.info("Finished Filtering: "+ (System.currentTimeMillis() - startTime)+" ms");
	}
	
	public static String filterTableData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap) {
	
		LOGGER.info("Filtering on table");
		long startTime = System.currentTimeMillis();
		
		String returnColumn = "";
		String[] columnHeaders = mainTree.getColumnHeaders();
		
		Map<String, Object[]> storedValues = new HashMap<String, Object[]>();
		for(String column: columnHeaders) {
			storedValues.put(column.toUpperCase(), mainTree.getUniqueValues(column));
		}
		
		Map<String, List<Object>> map = new HashMap<String, List<Object>>();
		for(String concept : filterValuesArrMap.keySet()) {
			map.put(concept.toUpperCase(), filterValuesArrMap.get(concept));
		}
		//need to find the which column is different from previous, then filter only that column
		//when first different column is found, call filterColumn on that column

		for(String columnHeader : columnHeaders) {
			String columnHeaderU = columnHeader.toUpperCase();
			Object[] storedValuesArr = storedValues.get(columnHeaderU);
			if(map.containsKey(columnHeaderU)) {
				List<Object> filterValuesArr = map.get(columnHeaderU);
				if(!equals(filterValuesArr, storedValuesArr)) {
					filterColumn(mainTree, columnHeader, filterValuesArr);
					returnColumn = columnHeader;
				}
			} 
			else {
				int totalSize = mainTree.getUniqueRawValues(columnHeader).length + mainTree.getFilteredUniqueRawValues(columnHeader).length;
				if(totalSize != storedValuesArr.length) {
					mainTree.unfilter(columnHeader);
					returnColumn = columnHeader;
				}
			}
		}
		
		LOGGER.info("Finished Filtering: "+ (System.currentTimeMillis() - startTime)+" ms");
		return returnColumn;
	}
	
	private static void filterColumn(ITableDataFrame mainTree, String concept, List<Object> filterValuesArr) {
		
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

		Object[] visibleValues = mainTree.getUniqueValues(concept);
		Set<Object> valuesToUnfilter = new HashSet<Object>(filterValuesArr);
		Set<Object> valuesToFilter = new HashSet<Object>(Arrays.asList(visibleValues));
		
		for(Object o : visibleValues) {
			valuesToUnfilter.remove(o);
		}
		
		for(Object o : filterValuesArr) {
			valuesToFilter.remove(o);
		}

		mainTree.filter(concept, new ArrayList<Object>(valuesToFilter));
		mainTree.unfilter(concept, new ArrayList<Object>(valuesToUnfilter));
		
		if(valuesToFilter.size() + valuesToUnfilter.size() > 0) {
			LOGGER.info("Filtered column: "+concept);
		}
	}
	
	private static boolean equals(List<Object> newColumn, Object[] oldColumn) {
		return newColumn.size() == oldColumn.length;
	}
	
	/**
	 * 
	 * @param tableID
	 * @param questionID
	 * @return - the ITableDataFrame associated with the tableID and/or questionID
	 */
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
	
	/**
	 * 
	 * @param table - to table from which to get flat data from
	 * @return - a list of maps, the preferred way of returning table data to the front end
	 */
	public static List<HashMap<String, Object>> getTableData(ITableDataFrame table) {
		LOGGER.info("Formatting Data from Table for the Front End");
		long startTime = System.currentTimeMillis();
		
		List<HashMap<String, Object>> returnData = TableDataFrameWebAdapter.getData(table);
		
		LOGGER.info("Formatted Data, returning to the Front End: "+(System.currentTimeMillis() - startTime)+" ms");
		return returnData;
	}
	
	public static boolean hasDuplicates(ITableDataFrame table, String[] columns) {
		String[] columnHeaders = table.getColumnHeaders();
		Map<String, Integer> columnMap = new HashMap<>();
		for(int i = 0; i < columnHeaders.length; i++) {
			columnMap.put(columnHeaders[i], i);
		}
		
		Iterator<Object[]> iterator = table.iterator(false);
		int numRows = table.getNumRows();
		Set<String> comboSet = new HashSet<String>(numRows);
		int rowCount = 1;
		while(iterator.hasNext()) {
			Object[] nextRow = iterator.next();
			String comboValue = "";
			for(String c : columns) {
				int i = columnMap.get(c);
				comboValue = comboValue + nextRow[i];
			}
			comboSet.add(comboValue);
			
			if(comboSet.size() < rowCount) {
				return true;
			}
			
			rowCount++;
		}
		boolean hasDuplicates = comboSet.size() != numRows;
		
		return hasDuplicates;
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
		
		columnHeader = columnHeader.substring(0, columnHeader.length() - 5);
		
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
