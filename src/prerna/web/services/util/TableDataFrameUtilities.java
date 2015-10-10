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
import prerna.util.ArrayUtilityMethods;
import prerna.util.QuestionPlaySheetStore;

public final class TableDataFrameUtilities {

	private TableDataFrameUtilities() {
		
	}
	
	public static void filterData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap) {
//		mainTree.unfilter();
		//boolean unfiltered = false;
		Set<String> keySet = filterValuesArrMap.keySet();
		String[] columnHeaders = mainTree.getColumnHeaders();
		String[] concepts = keySet.toArray(new String[keySet.size()]);
		for(String column : columnHeaders) {
			if(!ArrayUtilityMethods.arrayContainsValue(concepts, column)) {
				mainTree.unfilter(column);
			}
		}
		
		for(String concept: keySet) {

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
			Object[] superSet = mainTree.getUniqueValues(concept);

			int n = filterValuesArr.size();
			int m = superSet.length;

			if(m < n) {
				mainTree.unfilter();
				//unfiltered = true;
			} else {
				Comparator<Object> comparator = new Comparator<Object>() {
					public int compare(Object o1, Object o2) {
						return o1.toString().compareTo(o2.toString());
					}
				};

				//check if filterValuesArr is a subset of superSet
				Arrays.sort(superSet, comparator);
				Collections.sort(filterValuesArr, comparator);

				int i = 0;
				int j = 0;
				while(i < n && j < m) {
					int compareTo = superSet[i].toString().compareToIgnoreCase(filterValuesArr.get(i).toString());
					if(compareTo < 0) {
						j++;
					} else if(compareTo == 0) {
						j++; i++;
					} else if(compareTo > 0) {
						mainTree.unfilter();
						//unfiltered = true;
						break;
					}
				}
			}

//			List<Object> setDiff = new ArrayList<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
			Set<Object> totalSet = new HashSet<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
			for(Object o : filterValuesArr) {
				totalSet.remove(o);
			}
//			setDiff.removeAll(filterValuesArr);
			mainTree.filter(concept, new ArrayList<Object>(totalSet));
		}
	}
	
	public static void filterData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap, Map<String, Object[]> storedValues) {
		if(storedValues== null) {
			for(String concept : filterValuesArrMap.keySet()) {
				mainTree.filter(concept, filterValuesArrMap.get(concept));
			}
			return;
		}
		//need to find the which column is different from previous, then filter only that column
		//when first different column is found, call filterColumn on that column
		String[] columnHeaders = mainTree.getColumnHeaders();
		for(String columnHeader : columnHeaders) {
			Object[] storedValuesArr = storedValues.get(columnHeader);
			if(filterValuesArrMap.containsKey(columnHeader)) {
				List<Object> filterValuesArr = filterValuesArrMap.get(columnHeader);
				if(!equals(filterValuesArr, storedValuesArr)) {
					filterColumn(mainTree, columnHeader, filterValuesArr);
					return;
				}
			} 
			else {
				int totalSize = mainTree.getUniqueRawValues(columnHeader).length + mainTree.getFilteredUniqueRawValues(columnHeader).length;
				if(totalSize != storedValuesArr.length) {
					mainTree.unfilter();
				}
			}
		}
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

//		//if filterValuesArr not a subset of superSet, then unfilter
		Object[] superSet = mainTree.getUniqueValues(concept);

		int n = filterValuesArr.size();
		int m = superSet.length;

		if(m < n) {
			mainTree.unfilter();
			//unfiltered = true;
		} else {
			Comparator<Object> comparator = new Comparator<Object>() {
				public int compare(Object o1, Object o2) {
					return o1.toString().compareTo(o2.toString());
				}
			};

			//check if filterValuesArr is a subset of superSet
			Arrays.sort(superSet, comparator);
			Collections.sort(filterValuesArr, comparator);

			int i = 0;
			int j = 0;
			while(i < n && j < m) {
				int compareTo = superSet[i].toString().compareToIgnoreCase(filterValuesArr.get(i).toString());
				if(compareTo < 0) {
					j++;
				} else if(compareTo == 0) {
					j++; i++;
				} else if(compareTo > 0) {
					mainTree.unfilter();
					//unfiltered = true;
					break;
				}
			}
		}

//		List<Object> setDiff = new ArrayList<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
		Set<Object> totalSet = new HashSet<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
		for(Object o : filterValuesArr) {
			totalSet.remove(o);
		}
//		setDiff.removeAll(filterValuesArr);
		mainTree.filter(concept, new ArrayList<Object>(totalSet));
	}
	
	private static boolean equals(List<Object> newColumn, Object[] oldColumn) {
		return newColumn.size() == oldColumn.length;
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
