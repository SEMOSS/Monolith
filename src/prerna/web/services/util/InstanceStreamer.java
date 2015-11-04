/**
 *  This class provides an interface to interact with a column 
 *  of data, for:
 *            (1) quickly returning all instances that begin with a
 *          given letter or phrase
 *      (2) quickly returning all instances that contain a
 *          given letter or phrase
 *      (3) returning unique items I through J 
 *  
 *  This class is type-safe.
 *  
 *  @author Jason Adleberg
 */
package prerna.web.services.util;

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.RandomStringUtils;

import prerna.util.Utility;

public class InstanceStreamer {

	private ArrayList<Object> list;
	private int size;
	public static final String KEY = "blah";
	private String ID;

	public InstanceStreamer(List<Object> tempList) {
		Set<Object> set = new LinkedHashSet<Object>(tempList);
		list = new ArrayList<Object>(set);
		sortList(list); // sort into order: O(N log N)
		size = list.size();
	}

	public void setID(String tempID) {
		ID = tempID;
	}

	public String getID() {
		return ID;
	}

	/**
	 * Get unique items start (inclusive) through end (exclusive).
	 * This is accomplished by creating a TreeSet item from the ArrayList.
	 * 
	 * @param start                 index of start unique row (inclusive)
	 * @param end                   index of end unique row (exclusive)
	 * @return						Object[] of unique rows start (inclusive) through end (exclusive)
	 */
	public ArrayList<Object> getUnique(int start, int end) {
		if (start > size) { return new ArrayList<Object>(); }
		if (start < 0)  { start = 0; };
		if (end > size) { end = size; };
		return new ArrayList<Object>(list.subList(start, end));
	}

	/**
	 * Return number of unique items in list.
	 * @return	int of items in list
	 */
	public int getSize() {
		return list.size();
	}
	
	public ArrayList<Object> getList() {
		return this.list;
	}

	/**
	 * Implementation of binary search. Returns the first index that 
	 * starts with the search term: O(lg N)
	 * 
	 * @param searchTerm			key to search for
	 * @param lo					low bound of index
	 * @param hi					high bound of index
	 * @return						first index that starts with the search term
	 */
	private int findFirstTerm(String searchTerm, int lo, int hi) {

		if (hi < lo) {   
			return -1; // not found
		}                              
		int mid = lo + ((hi - lo) / 2);

		// use .getInstanceName() to strip URI
		String middleTerm = Utility.getInstanceName(list.get(mid).toString()).toLowerCase();

		if (middleTerm.indexOf(searchTerm) == 0) {
			return mid;         
		}
		else if (middleTerm.compareTo(searchTerm) > 0) {
			return findFirstTerm(searchTerm, lo, mid-1);
		}
		else {
			return findFirstTerm(searchTerm, mid+1, hi);  
		}
	}

	/**
	 * Returns ArrayList of all values that start with term searchTerm.
	 * Searching is case-insensitive; this is accomplished by using String.toLowerCase().
	 * O(lg N) time.
	 * @param searchTermObj						key to search for
	 * @return									list of values
	 */
	public ArrayList<Object> search(Object searchTermObj) {
		String searchTerm = searchTermObj.toString().toLowerCase();

		ArrayList<Object> results = new ArrayList<Object>();

		// get first index of term
		int firstIndex = findFirstTerm(searchTerm, 0, size-1);  

		// if result found: move left and right from firstIndex to find more 
		if (firstIndex != -1) {
			results.add(list.get(firstIndex).toString()); // add first value
			for (int i = firstIndex-1; i >= 0; i--) {     // add all relevant values to the left
				String value = Utility.getInstanceName(list.get(i).toString()).toLowerCase();
				if (value.startsWith(searchTerm)) {
					results.add(list.get(i).toString());
				}
				else break;
			}
			for (int i = firstIndex+1; i < size; i++) {   // add all relevant values to the right
				String value = Utility.getInstanceName(list.get(i).toString()).toLowerCase();
				if (value.startsWith(searchTerm)) {
					results.add(list.get(i).toString());
				}
				else break;
			}
		}

		sortList(results); // sort results into order
		return results;
	}

	/**
	 * Returns ArrayList of all values that contain term searchTerm.
	 * Searching is case-insensitive; this is accomplished by using String.toLowerCase().
	 * O(N) time.
	 * @param searchTermObj						key to search for
	 * @return									list of values
	 */
	public ArrayList<Object> regexSearch(Object searchTermObj) {
		String searchTerm = searchTermObj.toString().toLowerCase();

		ArrayList<Object> results = new ArrayList<Object>();

		for (int i = 0; i < list.size(); i++) {
			String value = Utility.getInstanceName(list.get(i).toString()).toLowerCase();
			if (value.contains(searchTerm)) {
				results.add(list.get(i).toString());
			}
		}

		return results; // results are already sorted.
	}

	/**
	 * Custom comparator for list (since ArrayList of Objects and not Strings)
	 * 
	 * @param list                                                                   arrayList of Objects
	 */
	private void sortList(ArrayList<Object> list) {
		Collections.sort(list, new Comparator<Object>() {
			public int compare(Object o1, Object o2) {
				return o1.toString().toLowerCase().compareTo(o2.toString().toLowerCase());
			}
		});
	}

	/**
	 * Testing section. 
	 * 
	 * 1) Search for words starting with and containing 'b-a' in a list of 1000 random words
	 * 2) Identify all numbers starting with and containing '5' in this list
	 * 3) Create 1M random strings with length 10, time how long it takes to create tree, and search if any of them start with "tomato".
	 */
	public static void main(String[] args) {
		Object[] strArray = {"course","dearth","overseas","podcast","portrait","advertising","contract","clean","endurance","fresh","guitar","wear","jackpot","tool","post","space","brunette","big","cheek","internet","high","sleep","have","interior","musing","excite","rise","earth","strength","media","alcohol","time","emotion","pure","paper","topless","tribune","relief","minute","group","retail","ringtone","value","note","kid","conflict","cute","win","flirt","forest","prepare","buy","design","office","compassion","question","look","place","golf","dictionary","happen","booth","recipe","strong","makeup","tower","call","category","carnival","stairway","punishment","exotic","begin","aspiration","climb","quality","manual","blur","rare","discovery","misc","society","petal","drugs","frame","world","student","silver","game","dead","flag","article","delicious","taxes","event","scenic","aim","dress","offer","past","drop","company","circus","priceless","degree","print","tiny","reward","show","independence","wonder","red","hear","visit","positive","temptation","run","destiny","compare","curiosity","girl","exercise","fragance","TV","babe","parent","score","tell","present","finish","marketing","reason","stop","lonely","desire","hair","original","cosmetic","make","music","audio","formation","bill","competition","drink","stone","shine","saint","gun","muscle","instant","trouble","respect","difficult","bike","talk","jump","nail","mini","chief","brainstorm","apprentice","toe","security","figure","tropic","one","well","adult","life","directory","accountant","acrobat","consider","together","funny","frozen","panties","section","town","stress","race","install","communication","gold","terrorism","age","ruin","organ","mask","greeting","discuss","affection","java","DIY","observe","romance","research","depend","consumer","eternity","engine","diversity","journey","commute","need","democracy","thousand","lady","robe","mother","electronic","clear","software","white","must","star","complex","mile","huge","lost","spice","build","fruit","home","splash","warm","skirt","clown","pool","surprise","puppy","face","buddy","destination","result","noise","wireless","hero","object","wrong","fly","skill","stationary","product","tourism","sound","mentor","egg","mob","baby","dragon","sugar","shop","sophisticated","background","work","boy","CD","flash","aviation","support","archive","black","restaurant","blood","nightlife","architecture","lust","athlete","piece","laugh","religion","resort","phone","doctor","outdoors","copy","spiritual","dark","soldier","relationship","afraid","sail","record","story","tribute","gadget","hour","device","position","root","seduce","commerce","meet","men","label","open","expand","select","exterior","care","road","quote","halloween","play","attention","spray","thin","follow","born","fireworks","practice","problem","tip","song","answer","point","idea","fantasy","courage","garden","holiday","smell","public","fun","enter","DVD","heroic","general","movie","hardware","naked","cellphone","agree","medecine","diet","size","trip","concept","busy","emergency","name","auction","detail","herb","newlywed","cruise","forever","real","decide","future","arrest","sign","loan","logo","doll","senior","thick","tattoo","water","process","sport","shirt","store","neon","smooth","passion","party","long","branch","concentration","loud","suburb","carry","exact","sword","now","reach","artist","clothe","ray","subway","adorable","groom","learn","bone","edge","ice","young","thanksgiving","bad","out","defeat","land","stuff","ceremony","police","roulette","because","heavy","camera","update","guess","pop","nostalgia","please","MP3","spend","deep","homosexuality","take","fight","download","system","basket","poem","luxury","dog","good","motor","master","blow","sell","element","case","blog","beast","create","indoor","cuisine","portable","erotic","repeat","free","climate","planet","extravaganza","lake","save","silence","blackjack","chat","stability","air","tradition","orchestra","palm","skin","curve","dry","suggest","rental","modern","happy","anger","share","pier","smoke","find","finger","film","female","say","minority","gamble","safe","flat","number","card","nation","pet","calm","beverage","oil","island","thong","symbol","money","beach","PC","local","politic","exit","war","end","attitude","despair","rainbow","today","order","magic","special","bazaar","quick","motivation","major","leisure","sad","palace","picture","court","comfort","seat","metal","entertainment","soccer","pub","trade","ocean","sea","style","dawn","business","moon","patriot","rule","base","laundry","husband","generation","summer","bottom","joke","cold","television","empty","platform","adolescent","street","listen","bell","cyber","park","hand","speech","reserve","cry","area","spot","drive","wine","sin","never","email","culture","radio","community","stick","cinema","favor","exam","environment","first","iron","imagine","attractive","correct","body","quiet","disco","speed","simple","change","sharp","intense","customer","bride","lead","finance","humor","furniture","heat","million","sensation","precision","car","human","journal","ready","plant","dear","love","spam","queen","motion","fitness","melody","vote","aggression","flower","nature","miracle","path","elegance","poker","choice","wife","optimism","online","prove","join","hi-tech","gender","dream","job","nude","solitude","leave","church","bright","energy","private","woman","lounge","sex","shape","kill","mystery","color","food","clock","center","always","temperature","calorie","push","off","basic","maturity","honor","short","bonding","desktop","addict","health","jewelry","innovation","rally","art","mate","mirror","laptop","VOIP","mortgage","traffic","raise","burn","explode","great","rain","activity","crime","theater","barbary","campus","nobody","demonstration","fish","random","pink","nothing","bank","stay","risk","decoration","sofa","shadow","search","costume","smile","lesbian","final","experience","thing","broadband","scream","main","museum","heart","government","weather","cartoon","mix","blonde","ask","paradise","cloud","dollar","enforcement","lyric","feet","grocery","toy","gorgeous","light","digital","relaxation","habitat","bar","notice","earring","bikini","chocolate","friend","military","dance","fix","close","luck","sky","glass","know","supreme","old","mouth","hope","lingerie","barefoot","hot","send","wild","capitalism","behind","feeling","hug","example","solidarity","actor","legal","day","desert","gossip","solver","liberation","bread","hobby","wish","majestic","father","go","comic","cat","message","cycle","goddess","joy","annual","chick","poor","casino","identity","hurricane","sensual","lips","gay","protest","eat","people","apartment","stage","bizarre","confidence","butt","taxi","cemetery","machine","rose","coast","appear","year","slim","technology","phenomena","lawyer","fashion","like","claim","oxygen","rescue","football","ecology","innocence","fast","gas","fact","cream","entrance","sun","enemy","tear","school","expect","straight","season","surf","rich","target","male","might","glamour","apple","memory","education","band","departure","pride","will","trap","murmur","survival","justice","brother","leg","fear","college","sister","chest","bed","gift","match","allow","border","credit","library","pay","interest","mind","yes","board","move","deal","full","AIDS","grow","screensaver","review","science","phrase","supermarket","force","declaration","wallpaper","graphic","fat","pollution","scale","cowboy","illusion","famous","mood","range","president","slow","peace","boat","website","noon","both","do","cook","break","perfume","soda","crowd","philosophy","step","century","marriage","international","press","weight","christmas","hiphop","jazz","think","forward","daughter","club","anime","celebration","possible","breast","hit","start","action","bath","develop","fame","advice","news","best","power","reply","allergy","character","accessory","eye","magazine","true","depression","cash","spectacular","birthday","animal","sympathy","beautiful","relative","landscape","spider","video","steam","effect","hotel","mountain","history","house","gateway","different","tan","subject","glad","success","silhouette","complete","translation","grass","treasury","help","pupil","next","industry","model","cost","arrive","captive","ticket","single","map","pretty","satisfaction","teach","dirt","king","funky","coffee","computer","remember","believe","tribal","fair","wait","family","baseball","mobile","track","against","connect","come","continent","angel","direction","obituary","chance","arrow","conversation","wide","expression","about","limit","view","jeans","shoe","activist","flame","couple","cool","concert","royalty","guide","danger","handmade","book","alone","army","provide","beat","turn","language","lottery","kiss","photo","mail","travel","city","graduate"};
		Object[] intArray = {87,52,85,97,51,68,18,31,57,11,36,76,96,91,96,12,68,23,57,44,16,84,2,35,32,15,31,64,2,84,31,83,49,45,50,86,16,54,13,22,29,36,6,35,28,83,63,71,77,62,11,95,92,98,46,88,94,77,9,24,49,86,14,18,15,47,86,60,68,73,22,13,3,69,91,57,80,66,81,87,61,70,74,70,73,6,12,56,59,29,81,71,95,76,96,90,32,24,31,93};

		// try with strings
		ArrayList<Object> arrayList = new ArrayList<Object>(Arrays.asList(strArray));
		InstanceStreamer stringTest = new InstanceStreamer(arrayList);
		System.out.println("Searching for all strings that start with 'ba':");
		Iterable<Object> searchResults = stringTest.search("ba");
		for (Object o: searchResults) {
			System.out.println(o.toString());
		}
		System.out.println("Searching for all strings that contain 'ba':");
		searchResults = stringTest.regexSearch("ba");
		for (Object o: searchResults) {
			System.out.println(o.toString());
		}

		// try with ints
		ArrayList<Object> intList = new ArrayList<Object>(Arrays.asList(intArray));
		stringTest = new InstanceStreamer(intList);
		System.out.println("Searching for all numbers that start with 5:");
		searchResults = stringTest.search("5");
		for (Object o: searchResults) {
			System.out.println(o.toString());
		}
		System.out.println("Searching for all numbers that contain 5:");
		searchResults = stringTest.regexSearch("5");
		for (Object o: searchResults) {
			System.out.println(o.toString());
		}

		//            create 1M Strings of length 10
		ArrayList<Object> bigList = new ArrayList<Object>();
		System.out.println("Generating random strings:");
		int sampleSize = 1000000;
		for (int i = 0; i < sampleSize; i++) {
			bigList.add(RandomStringUtils.randomAlphabetic(10));
			if (i % (sampleSize/100) == 0) {System.out.print(i/(sampleSize/100)+"% ");}
		}

		// load into tree and time it
		System.out.println();
		System.out.print("Loading into Tree:");
		long start = System.currentTimeMillis();
		stringTest = new InstanceStreamer(bigList);
		long t = System.currentTimeMillis() - start;
		System.out.println("took " + t + "ms.");
		System.out.println();

		// search for words with t, to, tom, toma, tomat, tomato
		start = System.currentTimeMillis();
		searchResults = stringTest.search("t");
		t = System.currentTimeMillis() - start;
		System.out.println("1 char: " + t + "ms.");
		assertTrue("Timing, char", t < 150);

		start = System.currentTimeMillis();
		searchResults = stringTest.search("to");
		t = System.currentTimeMillis() - start;
		assertTrue("Timing, 2char", t < 10);
		System.out.println("2 char: " + t + "ms.");

		start = System.currentTimeMillis();
		searchResults = stringTest.search("tom");
		t = System.currentTimeMillis() - start;
		assertTrue("Timing, 3char", t < 10);
		System.out.println("3 char: " + t + "ms.");

		start = System.currentTimeMillis();
		searchResults = stringTest.search("toma");
		t = System.currentTimeMillis() - start;
		System.out.println("4 char: " + t + "ms.");
		assertTrue("Timing, 4char", t < 10);
		for (Object o: searchResults) {
			System.out.println(o.toString());
		}

		start = System.currentTimeMillis();
		searchResults = stringTest.search("tomat");
		t = System.currentTimeMillis() - start;
		System.out.println("5 char: " + t + "ms.");
		assertTrue("Timing, 5char", t < 10);
		for (Object o: searchResults) {
			System.out.println(o.toString());
		}

		start = System.currentTimeMillis();
		searchResults = stringTest.search("tomato");
		t = System.currentTimeMillis() - start;
		System.out.println("6 char: " + t + "ms.");
		assertTrue("Timing, 6char", t < 10);
		for (Object o: searchResults) {
			System.out.println(o.toString());
		}

		System.out.println("Testing complete.");

	}
}