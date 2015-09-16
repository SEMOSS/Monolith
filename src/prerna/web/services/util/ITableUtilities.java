package prerna.web.services.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import prerna.algorithm.api.ITableDataFrame;

public class ITableUtilities {

	private ITableUtilities() {
		
	}
	
	public static void filterData(ITableDataFrame mainTree, Map<String, List<Object>> filterValuesArrMap) {
		mainTree.unfilter();
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
//			if(filterValuesArr == null || filterValuesArr.isEmpty()) {
//				Map<String, Object> retMap = new HashMap<String, Object>();
//				retMap.put("tableID", tableID);
//				return Response.status(200).entity(WebUtility.getSO(retMap)).build();
//			}

			//if filterValuesArr not a subset of superSet, then unfilter
//			Object[] superSet = mainTree.getUniqueValues(concept);
//
//			int n = filterValuesArr.size();
//			int m = superSet.length;
//
//			if(m < n) {
//				mainTree.unfilter();
//				unfiltered = true;
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
//						unfiltered = true;
//						break;
//					}
//				}
//			}

			List<Object> setDiff = new ArrayList<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
//			Set<Object> totalSet = new HashSet<Object>(Arrays.asList(mainTree.getUniqueValues(concept)));
//			for(Object o : filterValuesArr) {
//				totalSet.remove(o);
//			}
			setDiff.removeAll(filterValuesArr);
			mainTree.filter(concept, setDiff);
		}
	}
	
	public static void unfilterData() {
		
	}
}
