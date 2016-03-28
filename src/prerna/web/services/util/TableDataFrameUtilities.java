package prerna.web.services.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import prerna.algorithm.api.ITableDataFrame;

public final class TableDataFrameUtilities {

	private static final Logger LOGGER = LogManager.getLogger(TableDataFrameUtilities.class.getName());
	
	private TableDataFrameUtilities() {
		
	}
	
//	public static void filterData(ITableDataFrame mainTree, Map<String, Map<String, Object>> filterModel) {
//
//		LOGGER.info("Filtering on table");
//		long startTime = System.currentTimeMillis();
//		
//		mainTree.unfilter();
//		for(String key : filterModel.keySet()) {
//			Map<String, Object> columnMap = filterModel.get(key);
//			List<Object> values = (List<Object>)columnMap.get("values");
//			if(values.size() == 0) {
//				Boolean selectAll = (Boolean)columnMap.get("selectAll");
//				if(!selectAll) {
//					mainTree.filter(key, values);
//				}
//			} else {
//				mainTree.filter(key, new ArrayList<Object>(values));
//			}
//		}
//		
//		LOGGER.info("Finished Filtering: "+ (System.currentTimeMillis() - startTime)+" ms");
//	}
	
	/**
	 * 
	 * @param mainTree - table to filter on
	 * @param filterValuesArrMap - values to filter on
	 * 
	 * filters the table data frame
	 */
	public static void filterTableData(ITableDataFrame mainTree, Map<String, Map<String, Object>> filterValuesArrMap) {	
		LOGGER.info("Filtering on table");
		long startTime = System.currentTimeMillis();
		
		//key represents the column to filter
		for(String key : filterValuesArrMap.keySet()) {
			Map<String, Object> columnMap = filterValuesArrMap.get(key);
			
			//grab the values for the column to filter
			List<Object> values = (List<Object>)columnMap.get("values");
			
			//if values is empty it indicates to either keep everything or filter everything, this is done for optimization
			//selectAll boolean indicates which to do
			if(values.size() == 0) {
				Boolean selectAll = (Boolean)columnMap.get("selectAll");
				if(selectAll) {
					//unfilter the column
					mainTree.unfilter(key);
				} else {
					//filter the column
					mainTree.filter(key, values);
//					filterColumn(mainTree, key, values);
				}
			} else {
				//filter the column
				mainTree.filter(key, values);
//				filterColumn(mainTree, key, values);
			}
		}
		LOGGER.info("Finished Filtering: "+ (System.currentTimeMillis() - startTime)+" ms");
	}

	/**
	 * 
	 * @param mainTree
	 * @param concept
	 * @param filterValuesArr
	 */
//	public static void filterColumn(ITableDataFrame mainTree, String concept, List<Object> filterValuesArr) {
//		
//		// if the column is numeric, convert the values to doubles
//		if(mainTree.isNumeric(concept)) {
//			List<Object> values = new ArrayList<Object>(filterValuesArr.size());
//			for(Object o: filterValuesArr) {
//				try {
//					values.add(Double.parseDouble(o.toString()));
//				} catch(Exception e) {
//					values.add(o);
//				}
//			}
//			filterValuesArr = values;
//		}
////		if(filterValuesArr.isEmpty()) {
//			return;
//		}
//
		//filter the table
//		mainTree.filter(concept, new ArrayList<Object>(filterValuesArr));
//	}

//	/**
//	 * 
//	 * @param table - to table from which to get flat data from
//	 * @return - a list of maps, the preferred way of returning table data to the front end
//	 */
//	public static List<HashMap<String, Object>> getTableData(ITableDataFrame table) {
//		LOGGER.info("Formatting Data from Table for the Front End");
//		long startTime = System.currentTimeMillis();
//		
//		List<HashMap<String, Object>> returnData = TableDataFrameWebAdapter.getData(table);
//		
//		LOGGER.info("Formatted Data, returning to the Front End: "+(System.currentTimeMillis() - startTime)+" ms");
//		return returnData;
//	}
	
//	/**
//	 * 
//	 * @param table - to table from which to get flat data from
//	 * @return - a list of maps, the preferred way of returning table data to the front end
//	 */
//	public static List<HashMap<String, Object>> getTableData(ITableDataFrame table, String concept, String sort, int startRow, int endRow) {
//		LOGGER.info("Formatting Data from" +  table + ", " + concept + ", " + sort + ", " + startRow + ", and " + endRow + " for the Front End");
//		long startTime = System.currentTimeMillis();
//		
//		List<HashMap<String, Object>> returnData = TableDataFrameWebAdapter.getData(table, concept, sort, startRow, endRow);
//		
//		LOGGER.info("Formatted Data, returning to the Front End: "+(System.currentTimeMillis() - startTime)+" ms");
//		return returnData;
//	}
	
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

//	public static Object[] getExploreTableFilterModel(ITableDataFrame table) {
//		return TableDataFrameWebAdapter.getRawFilterModel(table);
//	}
}
