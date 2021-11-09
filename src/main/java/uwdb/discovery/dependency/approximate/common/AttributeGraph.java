package uwdb.discovery.dependency.approximate.common;

import uwdb.discovery.dependency.approximate.common.sets.AttributeSet;
import uwdb.discovery.dependency.approximate.common.sets.IAttributeSet;

import java.util.*;

public class AttributeGraph {
    //use hashmap to store edges in  graph
    final Map<IAttributeSet, List<IAttributeSet>> map = new HashMap<>();
    int numAttributes;
    int numVertices;
    int numEdges;
    boolean bidirectional;

    //c'tor
    public AttributeGraph(int numAttributes, boolean bidirectional) {
        this.numAttributes = numAttributes;
        this.bidirectional = bidirectional;
    }

    //basic modifiers
    public void addVertex(IAttributeSet s) {
        if (hasVertex(s))
            return;

        map.put(s, new LinkedList<>());
        numVertices++;
    }

    public void addEdge(IAttributeSet source, IAttributeSet destination) {
        if (hasVertex(source) && hasVertex(destination) && hasEdge(source, destination))
            return;

        if (!map.containsKey(source))
            addVertex(source);

        if (!map.containsKey(destination))
            addVertex(destination);

        //create edge
        map.get(source).add(destination);
        numEdges++;

        if (bidirectional)
            map.get(destination).add(source);
    }

    //advances modifiers
    //build edge between two vertices - receives integer instead AttributeSet
    public void addEdgeInt(Integer source, Integer destination) {
        IAttributeSet X = new AttributeSet(numAttributes);
        IAttributeSet Y = new AttributeSet(numAttributes);

        X.add(source);
        Y.add(destination);
        addEdge(X, Y);
    }

    //add vertex - receives integer instead AttributeSet
    public void addVertexInt(int i) {
        IAttributeSet set = new AttributeSet(numAttributes);
        set.add(i);
        addVertex(set);
    }


    //basic const functions
    public boolean hasVertex(IAttributeSet s) {
        return map.containsKey(s);
    }

    public boolean hasEdge(IAttributeSet s, IAttributeSet d) {
        if (map.get(s) == null)
                return false;
        if (map.get(d) == null)
            return false;

        return (map.get(s).contains(d) || map.get(d).contains(s));
    }

    public int getVertexCount() {
        return numVertices;
    }

    public int getEdgesCount() {
        return numEdges;
    }

    public List<IAttributeSet> getNeighbors(IAttributeSet node) {
        //node not part of graph
        if (!hasVertex(node))
            return null;

        return map.get(node);
    }

    //check if vertex i has neighbors - receives integer instead AttributeSet
    public boolean hasNeighborsInt(int i) {
        IAttributeSet set = new AttributeSet(numAttributes);
        set.add(i);

        //iterate over all vertices
        //see if current set is neighbor to any
        for (IAttributeSet x : map.keySet()) {
            if (hasEdge(x, set))
                return true;
        }

        return false;
    }

    //temporary, risky
    public Map<IAttributeSet, List<IAttributeSet>> getMap() {
        return map;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

        for (IAttributeSet v : map.keySet()) {
            builder.append(v.toString()).append(": ");
            for (IAttributeSet w : map.get(v)) {
                builder.append(w.toString()).append(" ");
            }

            builder.append("\n");
        }

        return (builder.toString());
    }


    //for each vertex - run BFS to find connected component
    //result is list of sub-graphs, each represents individual connected component

    /*
    result - list of attribute graphs. using list because number of
                connected components unknown
    visited - list of visited nodes. using list is easier than array (syntax)

    component - essentially it's a smaller graph, i.e. graph
     */


    public List<AttributeGraph> getConnectedComponents() {
        //initialize result list
        List<AttributeGraph> result = new ArrayList<>();

        //avoid revisiting nodes
        List<IAttributeSet> visited = new ArrayList<>();
        //boolean[] visited = new boolean[numVertices];

        //main loop
        for (IAttributeSet node : map.keySet()) {

            //skip visited nodes
            if (visited.contains(node))
                continue;

            //if we've reached here - new connected component discovered

            //mark as visited
            visited.add(node);

            //initialize empty graph
            AttributeGraph component = new AttributeGraph(numAttributes, true);
            component.addVertex(node);

            //initialize queue for BFS
            Queue<IAttributeSet> Q = new LinkedList<>();
            Q.add(node);

            while (!Q.isEmpty()) {
                IAttributeSet i = Q.poll();
                List<IAttributeSet> nbrs = getNeighbors(i);

                //add to component
                for (IAttributeSet dest: nbrs) {
                    component.addEdge(i, dest);
                }

                //remove visited nodes from nbrs list
                nbrs.removeIf(visited::contains);

                //now mark visited & add to Q
                visited.addAll(nbrs);
                Q.addAll(nbrs);


            }

            //Q is empty - connected component fully discovered
            result.add(component);
        }

        return result;
    }
}