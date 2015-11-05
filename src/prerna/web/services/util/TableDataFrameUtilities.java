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
import prerna.ds.ITableWebAdapter;
import prerna.ds.TableDataFrameWebAdapter;
import prerna.util.ArrayUtilityMethods;

public final class TableDataFrameUtilities {

	private static final Logger LOGGER = LogManager.getLogger(TableDataFrameUtilities.class.getName());
	
	private TableDataFrameUtilities() {
		
	}
	
//	public static void filterData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap) {
//
//		LOGGER.info("Filtering on table");
//		long startTime = System.currentTimeMillis();
//		
//		String[] columnHeaders = mainTree.getColumnHeaders();
//		
//		Map<String, Object[]> storedValues = new HashMap<String, Object[]>();
//		for(String column: columnHeaders) {
//			storedValues.put(column.toUpperCase(), mainTree.getUniqueValues(column));
//		}
//		
//		Map<String, List<Object>> map = new HashMap<String, List<Object>>();
//		for(String concept : filterValuesArrMap.keySet()) {
//			map.put(concept.toUpperCase(), filterValuesArrMap.get(concept));
//		}
//		//need to find the which column is different from previous, then filter only that column
//		//when first different column is found, call filterColumn on that column
//
//		for(String columnHeader : columnHeaders) {
//			columnHeader = columnHeader.toUpperCase();
//			Object[] storedValuesArr = storedValues.get(columnHeader);
//			if(map.containsKey(columnHeader)) {
//				List<Object> filterValuesArr = map.get(columnHeader);
//				if(!equals(filterValuesArr, storedValuesArr)) {
//					filterColumn(mainTree, columnHeader, filterValuesArr);
//				}
//			} 
//			else {
//				int totalSize = mainTree.getUniqueRawValues(columnHeader).length + mainTree.getFilteredUniqueRawValues(columnHeader).length;
//				if(totalSize != storedValuesArr.length) {
//					mainTree.unfilter();
//				}
//			}
//		}
//		
//		LOGGER.info("Finished Filtering: "+ (System.currentTimeMillis() - startTime)+" ms");
//	}
	
	public static void filterTableData(ITableDataFrame mainTree, Map<String, Map<String, Object>> filterValuesArrMap) {
		
		LOGGER.info("Filtering on table");
		long startTime = System.currentTimeMillis();
		
////		String returnColumn = "";
//		String[] columnHeaders = mainTree.getColumnHeaders();
//		
////		Map<String, Object[]> storedValues = new HashMap<String, Object[]>();
////		Map<String, Integer> sizes = new HashMap<String, Integer>();
////		for(String column: columnHeaders) {
////			storedValues.put(column.toUpperCase(), mainTree.getUniqueValues(column));
////			sizes.put(column.toUpperCase(), mainTree.getUniqueRawValues(column).length + mainTree.getFilteredUniqueRawValues(column).length);
////		}
//		
//		Map<String, List<Object>> map = new HashMap<String, List<Object>>();
//		for(String concept : filterValuesArrMap.keySet()) {
//			map.put(concept.toUpperCase(), filterValuesArrMap.get(concept));
//		}
//		//need to find the which column is different from previous, then filter only that column
//		//when first different column is found, call filterColumn on that column
//
//		for(String columnHeader : columnHeaders) {
//			String columnHeaderU = columnHeader.toUpperCase();
////			Object[] storedValuesArr = storedValues.get(columnHeaderU);
//			if(map.containsKey(columnHeaderU)) {
//				List<Object> filterValuesArr = map.get(columnHeaderU);
////				if(!equals(filterValuesArr, storedValuesArr)) {
//					filterColumn(mainTree, columnHeader, filterValuesArr);
////					returnColumn = columnHeader;
////				}
//			} 
//			else {
//				
////				int totalSize = sizes.get(columnHeaderU);
////				if(totalSize != storedValuesArr.length) {
////					mainTree.unfilter(columnHeader);
////					returnColumn = columnHeader;
////				}
//			}
//		}
		
		for(String key : filterValuesArrMap.keySet()) {
			Map<String, Object> columnMap = filterValuesArrMap.get(key);
			List<Object> values = (List<Object>)columnMap.get("values");
			if(values.size() == 0) {
				Boolean selectAll = (Boolean)columnMap.get("selectAll");
				if(selectAll) {
					mainTree.unfilter();
				} else {
					filterColumn(mainTree, key, values);
				}
			} else {
				filterColumn(mainTree, key, values);
			}
		}
		LOGGER.info("Finished Filtering: "+ (System.currentTimeMillis() - startTime)+" ms");
//		return returnColumn;
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
	
//	private static boolean equals(List<Object> newColumn, Object[] oldColumn) {
//		if(newColumn.size() != oldColumn.length) {
//			return false;
//		} else {
//			//loop through and check values
//			HashSet<String> set = new HashSet<>();
//			for(Object o : newColumn) {
//				set.add(o.toString());
//			}
//			
//			for(Object o : oldColumn) {
//				set.remove(o);
//			}
//			
//			return set.size() == 0;
//		}
//	}

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
	
	/**
	 * 
	 * @param table - to table from which to get flat data from
	 * @return - a list of maps, the preferred way of returning table data to the front end
	 */
	public static List<HashMap<String, Object>> getTableData(ITableDataFrame table, String concept, String sort, int startRow, int endRow) {
		LOGGER.info("Formatting Data from" +  table + ", " + concept + ", " + sort + ", " + startRow + ", and " + endRow + " for the Front End");
		long startTime = System.currentTimeMillis();
		
		List<HashMap<String, Object>> returnData = TableDataFrameWebAdapter.getData(table, concept, sort, startRow, endRow);
		
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

	public static Object[] getExploreTableFilterModel(ITableDataFrame table) {
		return TableDataFrameWebAdapter.getRawFilterModel(table);
	}
}
