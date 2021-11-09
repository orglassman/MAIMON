package uwdb.discovery.dependency.approximate.search;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import uwdb.discovery.dependency.approximate.common.AttributeGraph;
import uwdb.discovery.dependency.approximate.common.AttributePair;
import uwdb.discovery.dependency.approximate.common.Constants;
import uwdb.discovery.dependency.approximate.common.Transversals;
import uwdb.discovery.dependency.approximate.common.dependency.JoinDependency;
import uwdb.discovery.dependency.approximate.common.sets.AttributeSet;
import uwdb.discovery.dependency.approximate.common.sets.IAttributeSet;
import uwdb.discovery.dependency.approximate.entropy.MasterCompressedDB;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class GraphBuilderNew {
    protected MasterCompressedDB mcDB;
    protected String outputDirPath;
    protected double threshold;
    protected int range;
    protected int numAttributes;

    /*
    Or Sept. 2021:
    Speed up mining process. First construct dependency graph based on following rule:
    if I(A;B|OMEGA) > threshold, then A and B are connected. Edge (A,B) is added to graph
     */
    private AttributeGraph graph;
    private final List<String> componentFiles;

    private final Map<AttributePair, Set<IAttributeSet>> minPairwiseSeps;

    private final Set<IAttributeSet> minedMinSeps;
    private static GraphBuilderNew currentlyExecuting;
    //private final Set<JoinDependency> fullMVDsOfMinSeps;

    public Set<JoinDependency> MinedJDsFromMinSeps;

    //public long timePreparingEntropies = 0;
    public long timeToGetConsistentJD = 0;
    public long calculatingIMeasure = 0;
    public long timeToMineJDsWithLHS = 0;
    public long timeInitMinSeps = 0;
    public long totalRunningTime;

    //public static int TIMEOUT = 20; //3600; //14400;

    public static volatile boolean STOP = false;

    boolean completedMiningAllMinSeps;
    boolean completedMiningAllFullMVDs;


    public static GraphBuilderNew getCurrentExecution() {
        return currentlyExecuting;
    }

    public static void setCurrentExecution(GraphBuilderNew MJD) {
        GraphBuilderNew.currentlyExecuting = MJD;
    }



    public MasterCompressedDB getDatasetObject() {
        return mcDB;
    }

    public int getNumAttributes() {
        return numAttributes;
    }

    public Collection<IAttributeSet> getDiscoveredDataDependencies() {
        return minedMinSeps;
    }

    public GraphBuilderNew(MasterCompressedDB mcDB, String outputDirPath, double threshold, int range, int numAttributes) {
        this.mcDB = mcDB;
        this.outputDirPath = outputDirPath;
        this.threshold = threshold;
        this.range = range;
        this.numAttributes = numAttributes;


        this.totalRunningTime = 0;

        minPairwiseSeps = new HashMap<>();
        minedMinSeps = new HashSet<>();
        IAttributeSet allAttSet = new AttributeSet(numAttributes);
        allAttSet.add(0, numAttributes);
        MinedJDsFromMinSeps = new HashSet<>();
        completedMiningAllMinSeps = false;
        completedMiningAllFullMVDs = false;
        componentFiles = new LinkedList<>();
    }

    public static double calculateJDMeasure(JoinDependency JD, MasterCompressedDB mcDB) {
        Iterator<IAttributeSet> it = JD.componentIterator();

        double componentParts = 0;
        IAttributeSet toCalc = JD.getlhs().clone();
        int numComponents = 0;
        while(it.hasNext()) {
            toCalc.or(it.next());

            componentParts += mcDB.getEntropy(toCalc);
            toCalc.intersectNonConst(JD.getlhs());
            numComponents++;
        }

        double lhsPart = mcDB.getEntropy(JD.getlhs());
        double totalEntropy = mcDB.getTotalEntropy();

        double JDMeasure = componentParts - (numComponents-1.0) * lhsPart - totalEntropy;
        if(JDMeasure < 0.0) {
            assert Math.abs(JDMeasure) < Constants.JDMIN_J_THRESH;
            JDMeasure = 0.0;
        }

        JD.setMeasure(JDMeasure);
        return JDMeasure;
    }

    public static boolean isGreaterThanEpsilon(double subtrahend, double minuend) {
        double diff = subtrahend - minuend;
        return (diff > Constants.JDMIN_J_THRESH);
    }

    public static boolean isLessThanEpsilon(double subtrahend, double minuend) {
        double diff = subtrahend - minuend;
        return (diff < Constants.JDMIN_J_THRESH);
    }




    //I(X;Y|rest) = H(X CUP rest) + H(Y CUP rest) - H(rest) - H(all)
    public double calculateElementalMVD(int X, int Y, IAttributeSet rest) {
        long startTime = System.currentTimeMillis();

        rest.add(X);
        double H_first = mcDB.getEntropy(rest);
        rest.remove(X);

        rest.add(Y);
        double H_second = mcDB.getEntropy(rest);
        rest.remove(Y);

        double H_rest = mcDB.getEntropy(rest);

        rest.add(X);
        rest.add(Y);
        double H_all = mcDB.getEntropy(rest);


        calculatingIMeasure += (System.currentTimeMillis() - startTime);
        return H_first + H_second - H_rest - H_all;
    }

    private JoinDependency mostSpecificJD(IAttributeSet lhs) {
        IAttributeSet rest = lhs.complement();
        JoinDependency JD = new JoinDependency(lhs);
        for (int i = rest.nextAttribute(0); i >= 0; i = rest.nextAttribute(i + 1)) {
            IAttributeSet singleiAttSet = new AttributeSet(lhs.length());
            singleiAttSet.add(i);
            JD.addComponent(singleiAttSet);
        }
        return JD;
    }

    private static class MergedJD {
        public JoinDependency JD;
        public int i;
        public int j;
        public MergedJD(JoinDependency JD, int i, int j) {
            this.JD = JD;
            this.i = i;
            this.j=j;
        }
    }

    public Set<JoinDependency> mineAllJDsWithLHSDFS(int AttX, int AttY,
                                                    IAttributeSet lhs, int limit, JoinDependency JDToStart) {

        long startTime = System.currentTimeMillis();

        if(JDToStart == null)
            return new HashSet<>(1); //return empty set

        double jdMeasure = GraphBuilderNew.calculateJDMeasure(JDToStart, mcDB);
        //Every component (above 2) can reduce at most epsilon if eliminated.
        //So, if removing all components does not lead to a good bound then abort.
        int componentFactor = JDToStart.getComponents().size() - 1;
        if(isGreaterThanEpsilon(jdMeasure, componentFactor*this.threshold))
            return new HashSet<>(1); //return empty set

        Stack<MergedJD> Q = new Stack<>();
        Q.push(new MergedJD(JDToStart, 0, 0));
        Set<JoinDependency> P = new HashSet<>();
        while(!Q.isEmpty() && !STOP) {
            MergedJD mergedJD = Q.pop();
            double iJD = GraphBuilderNew.calculateJDMeasure(mergedJD.JD,mcDB);
            if(!isGreaterThanEpsilon(iJD, this.threshold)) {
                P.add(mergedJD.JD);
                if(limit>0 && P.size() >= limit) {
                    break;
                }
            } else { //try to traverse one of the merges
                int numComponents = mergedJD.JD.numComponents();
                if(numComponents > 2) { //if it is <= 2 then we know that there is no JD
                    IAttributeSet[] JDArray = mergedJD.JD.toArray();
                    int newi = 0;
                    int newj = 0;
                    if(mergedJD.j < numComponents - 1) {
                        newi = mergedJD.i;
                        newj = mergedJD.j+1;
                    } else if (mergedJD.i < numComponents - 2) {
                        newi = mergedJD.i+1;
                        newj = newi+1;
                    }

                    if((newi < newj) &&
                            (JDArray[newi].contains(AttX) && JDArray[newj].contains(AttY)) ||
                            (JDArray[newi].contains(AttY) && JDArray[newj].contains(AttX)))
                    {
                        if(newj < numComponents-1)
                            newj++;
                        else if(newi + 1 < numComponents - 2) {
                            newi=newi+1;
                            newj=newi+1;
                        } else {
                            newi=0;
                            newj=0;
                        }
                    }

                    if(newi < newj) {
                        mergedJD.i = newi;
                        mergedJD.j = newj;
                        Q.push(mergedJD);
                        JoinDependency mergedij = mergedJD.JD.mergeComponents(newi, newj);
                        JoinDependency mergedijConsistent = getConsistentJDCandidate(AttX, AttY, lhs, mergedij);
                        if(mergedijConsistent!= null) {
                            double mergedJDMeasure = GraphBuilderNew.calculateJDMeasure(mergedijConsistent, mcDB);
                            if(!isGreaterThanEpsilon(mergedJDMeasure, (mergedijConsistent.getComponents().size()-2)*this.threshold))
                                Q.push(new MergedJD(mergedijConsistent, 0, 0));
                        }
                    }
                }
            }
        }

        timeToMineJDsWithLHS += (System.currentTimeMillis() - startTime);
        return P;
    }

    private double[] getComponentEntropies(JoinDependency JD) {
        IAttributeSet[] JDCOmponents = JD.toArray();
        double[] retVal = new double[JD.getComponents().size()];
        for(int i = 0; i < retVal.length; i++) { //compute the entropy of JDCOmponents[i]\cup JD.getLHS()
            IAttributeSet AS = JDCOmponents[i];
            AS.or(JD.getlhs());
            retVal[i] = mcDB.getEntropy(AS);
        }

        for(int att = JD.getlhs().nextAttribute(0); att >= 0; att = JD.getlhs().nextAttribute(att + 1)) {
            for(int i = 0; i < JD.getComponents().size(); i++) {
                JDCOmponents[i].remove(att); //return to previous situation without allocating memory
            }
        }

        return retVal;
    }

    private IAttributeSet mergeIfNeeded(JoinDependency JD,
                                        double[] componentEntropies, double lhsEntropy) {
        IAttributeSet[] JDCOmponents = JD.getComponentArray();

        //int length = componentEntropies.length - numMerges;
        int length = JD.numOfComponents();
        for(int i=0 ; i < length ; i++) {
            IAttributeSet first = JDCOmponents[i];
            double firste = componentEntropies[i];
            for(int j=i+1 ; j < length ; j++) {
                IAttributeSet second = JDCOmponents[j];
                double seconde = componentEntropies[j];
                IAttributeSet firstSecondCombined = first.union(second);
                firstSecondCombined.or(JD.getlhs());
                double mergedEntropy = mcDB.getEntropy(firstSecondCombined);
                //double imeasure = calcuateIMeasure(first,second, JD.getlhs(),mcDB);
                double imeasure = firste+seconde-(mergedEntropy+lhsEntropy);
                if(isGreaterThanEpsilon(imeasure, this.threshold)) {
                    //i and j must appear together in any constalation
                    IAttributeSet merged = JD.mergeComponentsNonConst(i,j);
                    int lastIndex = length - 1;
                    componentEntropies[i] = mergedEntropy;
                    componentEntropies[j] = componentEntropies[lastIndex];
                    return merged;
                }
            }
        }
        return null;
    }


    private JoinDependency getConsistentJDCandidate(int AttX, int AttY,
                                                    IAttributeSet lhs, JoinDependency JDtoStart) {
        //JoinDependency JD = mostSpecificJD(lhs); //starting point
        long startTime = System.currentTimeMillis();
        double lhse = mcDB.getEntropy(JDtoStart.getlhs());


        //check if even possible to have them separated.
        lhs.add(AttX);
        double lhsXe = mcDB.getEntropy(lhs);
        lhs.add(AttY);
        double XYLHSEntropy = mcDB.getEntropy(lhs);
        lhs.remove(AttX);
        double lhsYe = mcDB.getEntropy(lhs);
        lhs.remove(AttY);
        double basicIMeasure = lhsXe+lhsYe-lhse-XYLHSEntropy;
        if(isGreaterThanEpsilon(basicIMeasure, this.threshold)) {
            return null;
        }

        //IAttributeSet[] JDComponents = JD.toArray();
        double[] componentEntropies = getComponentEntropies(JDtoStart);

        IAttributeSet mergedComponent = mergeIfNeeded(JDtoStart, componentEntropies, lhse );
        while(mergedComponent != null) {
            if((mergedComponent.contains(AttX) && mergedComponent.contains(AttY))) {
                timeToGetConsistentJD+=(System.currentTimeMillis()-startTime);
                return null;
            }
            mergedComponent = mergeIfNeeded(JDtoStart, componentEntropies, lhse);
        }
        timeToGetConsistentJD+=(System.currentTimeMillis()-startTime);
        if(JDtoStart.numComponents() > 1)
            return JDtoStart;
        return null;
    }

    private JoinDependency reduceToMinJDReturnJD(int AttX, int AttY, IAttributeSet X) {
        IAttributeSet Y = X.clone();
        JoinDependency consistentJD = getConsistentJDCandidate(AttX, AttY, Y, mostSpecificJD(Y));
        if(consistentJD == null) return null;
        Set<JoinDependency> JD_Y = mineAllJDsWithLHSDFS(AttX, AttY, Y,1, consistentJD);
        if(JD_Y.isEmpty()) return null;


        JoinDependency toReturn = JD_Y.iterator().next();
        for(int i= Y.nextAttribute(0); i >= 0 ; i = Y.nextAttribute(i+1)) {
            Y.remove(i);
            JoinDependency mostSpecificToStart = mostSpecificJD(Y);
            consistentJD = getConsistentJDCandidate(AttX, AttY, Y, mostSpecificToStart);
            if(consistentJD == null) {
                Y.add(i);
                continue;
            }
            JD_Y = mineAllJDsWithLHSDFS(AttX, AttY, Y,1,consistentJD);
            if(JD_Y.isEmpty()) { //equivalent to: if nonempty, then remove
                Y.add(i);
            }
            else {
                toReturn = JD_Y.iterator().next();
            }
        }
        return toReturn;
    }

    private void mineAllMinSeps(int AttX, int AttY) {
        AttributePair XYPair = new AttributePair(AttX, AttY);

        if(!minPairwiseSeps.containsKey(XYPair)) { //no separator
            minPairwiseSeps.put(XYPair, new HashSet<>());
            return;
        }
        Set<IAttributeSet> minXYSeps= minPairwiseSeps.get(XYPair);
        IAttributeSet firstXYMinSep = minXYSeps.iterator().next();
        if(minXYSeps.size() == 1 && firstXYMinSep.cardinality() == (numAttributes-2)) {
            //no other minimal separators are possible, just return
            return;
        }

        boolean done = false;
        //should never return a set with either X or Y, since it only
        //return minimal transversals, and neither X nor Y can be
        //part of a minimal transversal
        Transversals minTransversals = new Transversals(minXYSeps, numAttributes);
        while(!done && !STOP) {
            JoinDependency CtrJD = null;
            while(minTransversals.hasNext()) {
                IAttributeSet minTransversal = minTransversals.next();
                assert !minTransversal.contains(AttX);
                assert !minTransversal.contains(AttY);

                minTransversal.flip(0, numAttributes); //take the complement
                minTransversal.remove(AttX);
                minTransversal.remove(AttY);

                JoinDependency JD0 = getConsistentJDCandidate(AttX, AttY, minTransversal, mostSpecificJD(minTransversal));
                if(JD0!= null) {
                    Set<JoinDependency> minTransversalJDs =
                            mineAllJDsWithLHSDFS(AttX, AttY, minTransversal,
                                    1,JD0);
                    if(!minTransversalJDs.isEmpty()) {
                        CtrJD = minTransversalJDs.iterator().next();
                        break;
                    }
                }
            }
            if(CtrJD == null) {
                done = true;
            }
            else {
                //IAttributeSet minLHS = reduceToMinJD(AttX, AttY,CtrJD.getlhs());
                JoinDependency newJD = reduceToMinJDReturnJD(AttX, AttY, CtrJD.getlhs());

                assert newJD != null;
                minedMinSeps.add(newJD.getlhs());
                MinedJDsFromMinSeps.add(newJD);
                minTransversals.addHyperedge(newJD.getlhs());
            }

        }

    }

    private void mineAllMinSeps() {

        //	Set<AttributePair> seperablePairs = minPairwiseSeps.keySet();
        for(int i=0 ; i < numAttributes  && !STOP; i++)
            for(int j=i+1 ; j< numAttributes  && !STOP ; j++)
                mineAllMinSeps(i, j);

        this.completedMiningAllMinSeps=true;

        Set<IAttributeSet> retVal;
        retVal = new HashSet<>();
        Collection<Set<IAttributeSet>> minimalSeparators = minPairwiseSeps.values();
        for(Set<IAttributeSet> SomePairMinSeps: minimalSeparators) {
            retVal.addAll(SomePairMinSeps);
        }

    }

    ////////////////////////////////////////////////////////////////////
    //  GRAPH METHODS   ////////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////
     /*
    Test out new heuristic to speed up mining process.
    1. Build graph of attributes based on following condition:
        If I(A;B|OMEGA-{AB}) > epsilon:
         draw edge A-B

    2. Run external application to mine minimal separators

    External EXE also outputs mined separators to a file
    */

    private void buildGraph() {
        System.out.println("-I- Building graph");
        this.graph = new AttributeGraph(numAttributes,true);

        IAttributeSet AS = new AttributeSet(numAttributes);
        AS.add(0, numAttributes);

        //iterate over all pairs, try to build edge
        //I(i;j | OMEGA-{ij})
        //for(int i = 0; i < numAttributes && !STOP; i++) {
        for(int i = 0; i < numAttributes; i++) {
                AS.remove(i);

            //to include isolated vertices, use this boolean
            boolean isolated = true;

                //reached last attribute
                if (i == numAttributes - 1) {
                    if (graph.hasNeighborsInt(i))
                        isolated = false;
                }


                for(int j = i + 1; j < numAttributes; j++) {
                //for(int j = i + 1; j < numAttributes && !STOP; j++) {
                    AS.remove(j);
                    double iMeasure = calculateElementalMVD(i, j, AS);

                    if(isGreaterThanEpsilon(iMeasure,this.threshold)) {
                        graph.addEdgeInt(i, j);
                        isolated = false;
                    }
                    AS.add(j);
                }
                //found isolated vertex - add it independently
                if (isolated) {
                    graph.addVertexInt(i);
                }

                AS.add(i);
        }
        System.out.println("-I- Graph is:");
        System.out.println(graph.toString());
    }

    //the triangulation executable cannot handle non-connected graphs
    //so instead, we send connected components
    //this function searches connected components and outputs them into a file
    private void searchGraph() throws IOException {
        List<AttributeGraph> components = graph.getConnectedComponents();
        System.out.println("-I- Writing graph to file");
        int i = 0;
        for (AttributeGraph component: components) {
            outputComponent(i, component);
            i++;
        }
}

    void outputComponent(int graphNumber, AttributeGraph component) throws IOException {
        String fileName = outputDirPath + "/" + "GRAPH" + graphNumber + ".RANGE." + range + ".THRESH." + threshold + ".csv";
        BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));

        //use 2D array to record edges already printed to output file
        int[][] visited = new int[numAttributes][numAttributes];

        //need to change this block entirely
        try {
            Map<IAttributeSet, List<IAttributeSet>> map = component.getMap();
            for (Map.Entry<IAttributeSet, List<IAttributeSet>> entry : map.entrySet()) {

                //parse key & nbrs array
                IAttributeSet key = entry.getKey();
                List<IAttributeSet> nbrs = entry.getValue();
                int numNbrs = nbrs.size();

                //convert key to string
                String keyStr = key.toString().split("(?!^)")[1];

                //2 distinct cases - if nbrs array empty, simply write the key and finish
                //otherwise - loop

                if (numNbrs == 0) {
                    writer.write(keyStr);
                } else {
                    for (IAttributeSet u : nbrs) {
                        //convert value to string
                        String valueStr = u.toString().split("(?!^)")[1];

                        //print edge if it hasn't been printed yet
                        if (!checkVisitedEdge(keyStr, valueStr, visited)) {
                            writer.write(keyStr + "," + valueStr + "\n");
                        }
                    }
                }
            }
        } catch(Exception e){
            e.printStackTrace();
        }

        writer.close();
        this.componentFiles.add(fileName);  //add to list of component files (later we send these to external app)
    }

    /*
    kill two birds with one stone:
    1. check if edge (u,v) or (v,u) exists
    2. update visited array (2D)
     */
    private boolean checkVisitedEdge(String source, String destination, int[][] visited) {
        int s = Integer.parseInt(source);
        int d = Integer.parseInt(destination);

        if (visited[s][d] == 1)
            return true;
        if (visited[d][s] == 1)
            return true;

        visited[s][d] = 1;
        visited[d][s] = 1;
        return false;
    }

    //"C:\Users\orgla\Desktop\Study\J_Divergence_ST_formulation\MinTriangulationsEnumeration\MinTriangulationsEnumeration.exe"
    //Run MinTriangulationsEnumeration.exe to mine minimal separators
    private void runExternal() throws IOException {
        System.out.println("-I- Running external triangulation EXE");
        //change current working directory (necessary for EXE)
        System.setProperty("user.dir", outputDirPath);

        //TEMPORARY: hardcoded path to enumeration EXE
        String externalPath = "C:\\Users\\orgla\\Desktop\\Study\\J_Divergence_ST_formulation\\MinTriangulationsEnumeration\\cmake-build-debug\\MinTriangulationsEnumeration.exe";

        for (String fileName: this.componentFiles) {
            Process process = new ProcessBuilder(externalPath, fileName, "print=all", "alg=separators").start();
            InputStream is = process.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            BufferedReader br = new BufferedReader(isr);
            String line;

            while ((line = br.readLine()) != null) {
                System.out.println(line);
            }
        }

    }

    ////////////////////////////////////////////////////////////////////
    //  END GRAPH METHODS   ////////////////////////////////////////////
    ////////////////////////////////////////////////////////////////////


    //set up test and run it thru 'executeTest'
    public static void executeTestsSingleDataset(String dataSetPath,
                                                 String outputDirPath, int numAttributes, double[] thresholds,  int[] rangeSizes, long timeout, boolean mineAllFullMVDs) {
        Path inputPath = Paths.get(dataSetPath);
        String inputFilename = inputPath.getFileName().toString();
        String outputFileName = inputFilename+".out.csv";
        Path outputPath = Paths.get(outputDirPath, outputFileName);

        System.out.println("-I- Executing tests of " + dataSetPath + "with " + numAttributes + " attributes, with runWithTimeout!");


        Writer writer;
        CSVPrinter csvPrinter;
        try
        {
            if(Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS)) {
                writer =Files.newBufferedWriter(outputPath,StandardOpenOption.APPEND,StandardOpenOption.SYNC);
                csvPrinter =
                        new CSVPrinter(writer, CSVFormat.DEFAULT);
            } else {
                writer = Files.newBufferedWriter(outputPath,StandardOpenOption.CREATE,StandardOpenOption.SYNC);
                csvPrinter =
                        new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader
                                ("#Attribtues", "#Rows", "Range Size",
                                        "Threshold", "Timeout (sec)", "Completed MinSeps", "#Minimal Separators",
                                        "Completed FullMVDs", "#Full MVDs",
                                        "Time Building range Tbls",
                                        "#In-Memory Queries Issued","Query Time",
                                        "total Running time","%querying",
                                        "Cached Entropy Objects",
                                        "Number of tuples processed during Entropy Computation"));
            }

            //main loop
            for (int rangeSize : rangeSizes) {
                for (double threshold : thresholds) {

                    double normalizedThreshold = threshold/numAttributes;

                    //normalize threshold by numAttributes
                    Callable<GraphBuilderNew> execution = () -> executeTest(dataSetPath, outputDirPath, numAttributes, rangeSize, normalizedThreshold);

                    RunnableFuture<GraphBuilderNew> future = new FutureTask<>(execution);
                    ExecutorService service = Executors.newSingleThreadExecutor();
                    service.execute(future);
                    GraphBuilderNew miningResult = null;
                    boolean cancelled = false;
                    try {
                        GraphBuilderNew.setCurrentExecution(null);
                        miningResult = future.get(timeout, TimeUnit.SECONDS);    // wait

                    } catch (TimeoutException ex) {
                        GraphBuilderNew.STOP = true;
                        System.out.println(Thread.currentThread().getId() + ":TIMEOUT!, stopping execution");
                        cancelled = future.cancel(true);
                        Thread.sleep(5000);
                        //fetch mining result
                        miningResult = GraphBuilderNew.getCurrentExecution();

                    } catch (InterruptedException | ExecutionException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                    service.shutdown();

                    //mining successful, print results to output files
                    if (miningResult != null) {
                        MasterCompressedDB DatasetObj = miningResult.getDatasetObject();
                        long totalRunTime = miningResult.totalRunningTime;
                        double percentQuerying = (double) DatasetObj.totalTimeSpentQuerying() / (double) totalRunTime;
                        percentQuerying = percentQuerying * 100.0;

                        //Writing records in the generated CSV file
                        csvPrinter.printRecord(DatasetObj.getNumAttributes(), DatasetObj.getNumRows(), rangeSize,
                                threshold, timeout, Boolean.toString(miningResult.completedMiningAllMinSeps),
                                miningResult.getDiscoveredDataDependencies().size(),
                                Boolean.toString((miningResult.completedMiningAllFullMVDs || !mineAllFullMVDs)),
                                DatasetObj.totalTimeBuildingRangeTables(),
                                DatasetObj.totalNumQueriesIsuues(), DatasetObj.totalTimeSpentQuerying(),
                                totalRunTime, percentQuerying,
                                DatasetObj.numberOfCachedEntropies(),
                                DatasetObj.numOfTuplesProcessedDuringEntropyComputation());
                        csvPrinter.flush();
                    } else {
                        System.out.println(Thread.currentThread().getId() + ": Did not fully create GraphBuilderNew object within timeout");
                        MasterCompressedDB.shutdown();
                    }
                }
            }
            csvPrinter.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch (Exception e) {
            MasterCompressedDB.shutdown();
        }

    }

    public static GraphBuilderNew executeTest(String dataSetPath, String outputDirPath, int numAttributes, int rangeSize,
                                              double threshold) throws IOException {
        //print head message
        long startTime = System.currentTimeMillis();
        GraphBuilderNew.STOP = false;
        System.out.println(Thread.currentThread().getId() +  ":Starting Execution " + dataSetPath + " threshold=" + threshold);

        //mcdb init
        MasterCompressedDB mcDB = new MasterCompressedDB(dataSetPath, numAttributes, rangeSize, false);
        mcDB.initDBs();
        System.out.println(Thread.currentThread().getId()+ ": after initDBs");

        //init miner
        GraphBuilderNew miner = new GraphBuilderNew(mcDB, outputDirPath, threshold, rangeSize, numAttributes);
        GraphBuilderNew.setCurrentExecution(miner);

        //main work
        miner.buildGraph();
        miner.searchGraph();
        miner.runExternal();

        //TODO: run external app and check separators
        //miner.runTriangulationMiner();

        System.out.println(Thread.currentThread().getId()+ ": calling mineAllMinSeps...");
        miner.mineAllMinSeps();
        System.out.println(Thread.currentThread().getId() + ": Completed mining all minSeps...");

        miner.totalRunningTime+=(System.currentTimeMillis()-startTime);
        miner.printRuntimeCharacteristics();
        MasterCompressedDB.shutdown();
        return miner;
    }

    public void printRuntimeCharacteristics()
    {
        System.out.println(Thread.currentThread().getId() + ": Runtime Characteristics:");
        System.out.printf(Thread.currentThread().getId() + ": Num Attributes: %d\n", mcDB.getNumAttributes());
        System.out.printf(Thread.currentThread().getId() +  ": Num Rows: %d\n", mcDB.getNumRows());
        System.out.printf(Thread.currentThread().getId() +  ": Threshold: %f\n", threshold);
        System.out.printf(Thread.currentThread().getId() +  ": Num In-memory Queries issued: %d\n", mcDB.totalNumQueriesIsuues());
        System.out.printf(Thread.currentThread().getId() + ": Time spend processing queries: %d\n", mcDB.totalTimeSpentQuerying());
        System.out.printf(Thread.currentThread().getId() +" Time finding consistent JD: %d\n", timeToGetConsistentJD);
        System.out.printf(Thread.currentThread().getId() +" Time spend calculating I-measure: %d\n", calculatingIMeasure);
        System.out.printf(Thread.currentThread().getId() +" Time spend initializing minimal separators: %d\n", timeInitMinSeps);
        System.out.printf(Thread.currentThread().getId() +" Number of separators mined: %d\n", this.minedMinSeps.size());
        System.out.printf(Thread.currentThread().getId() +" Number of JDs corresponding to minSeps: %d\n", this.MinedJDsFromMinSeps.size());
        System.out.printf(Thread.currentThread().getId() +" Avg. JDs per minsep: %f\n",
                (double)this.MinedJDsFromMinSeps.size()/(double)minedMinSeps.size());
    }

    //for single input file, mine all MVDs. hardcoded parameters
    public static void main(String[] args) {
        String inFile = args[0];
        int numAttributes = Integer.parseInt(args[1]);
        String outDir = args[2];

        //for now false
        boolean mineFullMVDs = false;
//        if(args.length > 3) {
//            if(!args[3].isEmpty()) {
//                mineFullMVDs = true;
//            }
//        }

        //hardcoded parameters
        double[] thresholds = {0.001, 0.002, 0.003, 0.004, 0.005, 0.01, 0.02, 0.03, 0.04, 0.05};
        int[] ranges  = {2};
        long timeout = 2000;

        System.out.println(Constants.SPACER);
        System.out.println("-I- Executing single test");
        System.out.println("-I- Input file:           " + inFile);
        System.out.println("-I- Output directory:     " + outDir);
        System.out.println("-I- Number of attributes: " + numAttributes);
        System.out.println("-I- Thresholds:           " + Arrays.toString(thresholds));
        System.out.println("-I- Ranges:               " + Arrays.toString(ranges));
        System.out.println("-I- Timeout:              " + timeout);
        System.out.println("-I- Mine full MVDs:       " + mineFullMVDs);
        System.out.println("");

        executeTestsSingleDataset(inFile, outDir, numAttributes, thresholds, ranges, timeout, mineFullMVDs);

        System.out.println(Constants.SPACER);
        System.out.println("-I- Main flow finished");
    }
}