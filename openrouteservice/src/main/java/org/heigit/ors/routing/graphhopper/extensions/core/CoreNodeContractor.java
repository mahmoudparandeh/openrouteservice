/*  This file is part of Openrouteservice.
 *
 *  Openrouteservice is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version 2.1
 *  of the License, or (at your option) any later version.

 *  This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.

 *  You should have received a copy of the GNU Lesser General Public License along with this library;
 *  if not, see <https://www.gnu.org/licenses/>.
 */
package org.heigit.ors.routing.graphhopper.extensions.core;

import com.graphhopper.routing.DijkstraOneToMany;
import com.graphhopper.routing.ch.PreparationWeighting;
import com.graphhopper.routing.ch.PrepareEncoder;
import com.graphhopper.routing.util.DefaultEdgeFilter;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.weighting.AbstractWeighting;
import com.graphhopper.storage.*;
import com.graphhopper.util.*;
import org.heigit.ors.routing.graphhopper.extensions.edgefilters.EdgeFilterSequence;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * This code is based on that from GraphHopper GmbH.
 *
 * @author Peter Karich
 * @author Hendrik Leuschner
 */

public class CoreNodeContractor {
    private final GraphHopperStorage ghStorage;
    private final CHGraph prepareGraph;
    private final PreparationWeighting prepareWeighting;
    private final CHProfile chProfile;
    private final DataAccess originalEdges;
    private final Map<Shortcut, Shortcut> shortcuts = new HashMap<>();
    private final AddShortcutHandler addScHandler = new AddShortcutHandler();
    private final CalcShortcutHandler calcScHandler = new CalcShortcutHandler();
    private CHEdgeExplorer vehicleInExplorer;
    private CHEdgeExplorer vehicleOutExplorer;
    private IgnoreNodeFilterSequence ignoreNodeFilterSequence;
    private EdgeFilter restrictionFilter;
    private DijkstraOneToMany prepareAlgo;
    private int addedShortcutsCount;
    private long dijkstraCount;
    private int maxVisitedNodes = Integer.MAX_VALUE;
    private StopWatch dijkstraSW = new StopWatch();
    private int maxEdgesCount;
    private int maxLevel;

    public CoreNodeContractor(Directory dir, GraphHopperStorage ghStorage, CHGraph prepareGraph, CHProfile chProfile) {
        if (chProfile.getTraversalMode().isEdgeBased()) {
            throw new IllegalArgumentException("Contraction Hierarchies only support node based traversal so far, given: " + chProfile.getTraversalMode());
        }
        // todo: it would be nice to check if ghStorage is frozen here
        this.ghStorage = ghStorage;
        this.prepareGraph = prepareGraph;
        this.prepareWeighting = new PreparationWeighting(chProfile.getWeighting());
        this.chProfile = chProfile;
        originalEdges = dir.find("original_edges_" + AbstractWeighting.weightingToFileName(chProfile.getWeighting()));
        originalEdges.create(1000);
    }

    public void initFromGraph() {
        // todo: do we really need this method ? the problem is that ghStorage/prepareGraph can potentially be modified
        // between the constructor call and contractNode,calcShortcutCount etc. ...
        maxLevel = prepareGraph.getNodes() + 1;
        maxEdgesCount = ghStorage.getAllEdges().length();
        ignoreNodeFilterSequence = new IgnoreNodeFilterSequence(prepareGraph, maxLevel);
        ignoreNodeFilterSequence.add(restrictionFilter);
        FlagEncoder prepareFlagEncoder = prepareWeighting.getFlagEncoder();
        vehicleInExplorer = prepareGraph.createEdgeExplorer(DefaultEdgeFilter.inEdges(prepareFlagEncoder));
        vehicleOutExplorer = prepareGraph.createEdgeExplorer(DefaultEdgeFilter.outEdges(prepareFlagEncoder));
        prepareAlgo = new DijkstraOneToMany(prepareGraph, prepareWeighting, chProfile.getTraversalMode());
    }

    public void close() {
        prepareAlgo.close();
        originalEdges.close();
    }

    public void setRestrictionFilter(EdgeFilter filter){
        this.restrictionFilter = filter;
    }

    public void setMaxVisitedNodes(int maxVisitedNodes) {
        this.maxVisitedNodes = maxVisitedNodes;
    }

    public long contractNode(int node) {
        shortcuts.clear();
        long degree = findShortcuts(addScHandler.setNode(node));
        addedShortcutsCount += addShortcuts(shortcuts.keySet());
        return degree;
    }

    public CalcShortcutsResult calcShortcutCount(int node) {
        findShortcuts(calcScHandler.setNode(node));
        return calcScHandler.calcShortcutsResult;
    }

    /**
     * Searches for shortcuts and calls the given handler on each shortcut that is found. The graph is not directly
     * changed by this method.
     * Returns the 'degree' of the handler's node (disregarding edges from/to already contracted nodes). Note that
     * here the degree is not the total number of adjacent edges, but only the number of incoming edges
     */
    private long findShortcuts(ShortcutHandler sch) {
        long tmpDegreeCounter = 0;
        EdgeIterator incomingEdges = vehicleInExplorer.setBaseNode(sch.getNode());
        // collect outgoing nodes (goal-nodes) only once
        while (incomingEdges.next()) {
            int uFromNode = incomingEdges.getAdjNode();
            // accept only not-contracted nodes, do not consider loops at the node that is being contracted
            if (uFromNode == sch.getNode() || isContracted(uFromNode))
                continue;

            final double incomingEdgeWeight = prepareWeighting.calcWeight(incomingEdges, true, EdgeIterator.NO_EDGE);
            // this check is important to prevent calling calcMillis on inaccessible edges and also allows early exit
            if (Double.isInfinite(incomingEdgeWeight)) {
                continue;
            }
            int skippedEdge1 = incomingEdges.getEdge();
            int incomingEdgeOrigCount = getOrigEdgeCount(skippedEdge1);
            // collect outgoing nodes (goal-nodes) only once
            EdgeIterator outgoingEdges = vehicleOutExplorer.setBaseNode(sch.getNode());
            // force fresh maps etc as this cannot be determined by from node alone (e.g. same from node but different avoidNode)
            prepareAlgo.clear();
            tmpDegreeCounter++;
            while (outgoingEdges.next()) {
                int wToNode = outgoingEdges.getAdjNode();
                // add only uncontracted nodes
                if (prepareGraph.getLevel(wToNode) != maxLevel || uFromNode == wToNode)
                    continue;

                // Limit weight as ferries or forbidden edges can increase local search too much.
                // If we decrease the correct weight we only explore less and introduce more shortcuts.
                // I.e. no change to accuracy is made.
                double existingDirectWeight = incomingEdgeWeight
                        + prepareWeighting.calcWeight(outgoingEdges, false, incomingEdges.getEdge());
                if (Double.isNaN(existingDirectWeight))
                    throw new IllegalStateException("Weighting should never return NaN values" + ", in:"
                            + getCoords(incomingEdges, prepareGraph) + ", out:" + getCoords(outgoingEdges, prepareGraph)
                            + ", dist:" + outgoingEdges.getDistance());

                if (Double.isInfinite(existingDirectWeight))
                    continue;

                prepareAlgo.setWeightLimit(existingDirectWeight);
                prepareAlgo.setMaxVisitedNodes(maxVisitedNodes);
                prepareAlgo.setEdgeFilter(ignoreNodeFilterSequence.setAvoidNode(sch.getNode()));

                dijkstraSW.start();
                dijkstraCount++;
                int endNode = prepareAlgo.findEndNode(uFromNode, wToNode);
                dijkstraSW.stop();

                // compare end node as the limit could force dijkstra to finish earlier
                if (endNode == wToNode && prepareAlgo.getWeight(endNode) <= existingDirectWeight)
                    // FOUND witness path, so do not add shortcut
                    continue;
                
                sch.foundShortcut(uFromNode, wToNode,
                        existingDirectWeight, 0,
                        outgoingEdges.getEdge(), getOrigEdgeCount(outgoingEdges.getEdge()),
                        skippedEdge1, incomingEdgeOrigCount);
            }
        }
        return tmpDegreeCounter;
    }

    /**
     * Adds the given shortcuts to the graph.
     *
     * @return the actual number of shortcuts that were added to the graph
     */
    private int addShortcuts(Collection<Shortcut> shortcuts) {
        int tmpNewShortcuts = 0;
        NEXT_SC:
        for (Shortcut sc : shortcuts) {
            boolean updatedInGraph = false;
            // check if we need to update some existing shortcut in the graph
            CHEdgeIterator iter = vehicleOutExplorer.setBaseNode(sc.from);
            while (iter.next()) {
                if (iter.isShortcut() && iter.getAdjNode() == sc.to) {
                    int status = iter.getMergeStatus(sc.flags);
                    if (status == 0)
                        continue;

                    if (sc.weight >= prepareWeighting.calcWeight(iter, false, EdgeIterator.NO_EDGE)) {
                        // special case if a bidirectional shortcut has worse weight and still has to be added as otherwise the opposite direction would be missing
                        // see testShortcutMergeBug
                        if (status == 2)
                            break;

                        continue NEXT_SC;
                    }

                    if (iter.getEdge() == sc.skippedEdge1 || iter.getEdge() == sc.skippedEdge2) {
                        throw new IllegalStateException("Shortcut cannot update itself! " + iter.getEdge()
                                + ", skipEdge1:" + sc.skippedEdge1 + ", skipEdge2:" + sc.skippedEdge2
                                + ", edge " + iter + ":" + getCoords(iter, prepareGraph)
                                + ", sc:" + sc
                                + ", skippedEdge1: " + getCoords(prepareGraph.getEdgeIteratorState(sc.skippedEdge1, sc.from), prepareGraph)
                                + ", skippedEdge2: " + getCoords(prepareGraph.getEdgeIteratorState(sc.skippedEdge2, sc.to), prepareGraph)
                                + ", neighbors:" + GHUtility.getNeighbors(iter));
                    }

                    // note: flags overwrite weight => call first
                    iter.setFlagsAndWeight(sc.flags, sc.weight);
                    iter.setSkippedEdges(sc.skippedEdge1, sc.skippedEdge2);
                    setOrigEdgeCount(iter.getEdge(), sc.originalEdges);
                    updatedInGraph = true;
                    break;
                }
            }

            if (!updatedInGraph) {
                int scId = prepareGraph.shortcut(sc.from, sc.to, sc.flags, sc.weight, sc.skippedEdge1, sc.skippedEdge2);
                setOrigEdgeCount(scId, sc.originalEdges);
                tmpNewShortcuts++;
            }
        }
        return tmpNewShortcuts;
    }

    private String getCoords(EdgeIteratorState edge, Graph graph) {
        NodeAccess na = graph.getNodeAccess();
        int base = edge.getBaseNode();
        int adj = edge.getAdjNode();
        return base + "->" + adj + " (" + edge.getEdge() + "); "
                + na.getLat(base) + "," + na.getLon(base) + " -> " + na.getLat(adj) + "," + na.getLon(adj);
    }

    int getAddedShortcutsCount() {
        return addedShortcutsCount;
    }

    boolean isContracted(int node) {
        return prepareGraph.getLevel(node) != maxLevel;
    }

    private void setOrigEdgeCount(int edgeId, int value) {
        edgeId -= maxEdgesCount;
        if (edgeId < 0) {
            // ignore setting as every normal edge has original edge count of 1
            if (value != 1)
                throw new IllegalStateException("Trying to set original edge count for normal edge to a value = " + value
                        + ", edge:" + (edgeId + maxEdgesCount) + ", max:" + maxEdgesCount + ", graph.max:" +
                        prepareGraph.getAllEdges().length());
            return;
        }

        long tmp = (long) edgeId * 4;
        originalEdges.ensureCapacity(tmp + 4);
        originalEdges.setInt(tmp, value);
    }

    private int getOrigEdgeCount(int edgeId) {
        edgeId -= maxEdgesCount;
        if (edgeId < 0)
            return 1;

        long tmp = (long) edgeId * 4;
        originalEdges.ensureCapacity(tmp + 4);
        return originalEdges.getInt(tmp);
    }

    String getPrepareAlgoMemoryUsage() {
        return prepareAlgo.getMemoryUsageAsString();
    }

    long getDijkstraCount() {
        return dijkstraCount;
    }

    void resetDijkstraTime() {
        dijkstraSW = new StopWatch();
    }

    public float getDijkstraSeconds() {
        return dijkstraSW.getSeconds();
    }

    static class IgnoreNodeFilterSequence extends EdgeFilterSequence implements EdgeFilter {
        int avoidNode;
        int maxLevel;
        CHGraph graph;

        public IgnoreNodeFilterSequence(CHGraph g, int maxLevel) {
            this.graph = g;
            this.maxLevel = maxLevel;
        }

        public IgnoreNodeFilterSequence setAvoidNode(int node) {
            this.avoidNode = node;
            return this;
        }

        @Override
        public final boolean accept(EdgeIteratorState iter) {
            // ignore if it is skipNode or adjNode is already contracted
            int node = iter.getAdjNode();
            if(!(avoidNode != node && graph.getLevel(node) == maxLevel)) return false;
            if (graph.isShortcut(iter.getEdge()))
                return true;
            return super.accept(iter);
        }
    }

    static class Shortcut {
        int from;
        int to;
        int skippedEdge1;
        int skippedEdge2;
        double dist;
        double weight;
        int originalEdges;
        int flags = PrepareEncoder.getScFwdDir();

        public Shortcut(int from, int to, double weight, double dist) {
            this.from = from;
            this.to = to;
            this.weight = weight;
            this.dist = dist;
        }

        @Override
        public int hashCode() {
            int hash = 5;
            hash = 23 * hash + from;
            hash = 23 * hash + to;
            return 23 * hash
                    + (int) (Double.doubleToLongBits(this.weight) ^ (Double.doubleToLongBits(this.weight) >>> 32));
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null || getClass() != obj.getClass())
                return false;

            final Shortcut other = (Shortcut) obj;
            return this.from == other.from && this.to == other.to &&
                    Double.doubleToLongBits(this.weight) == Double.doubleToLongBits(other.weight);

        }

        @Override
        public String toString() {
            String str;
            if (flags == PrepareEncoder.getScDirMask())
                str = from + "<->";
            else
                str = from + "->";

            return str + to + ", weight:" + weight + " (" + skippedEdge1 + "," + skippedEdge2 + ")";
        }
    }

    interface ShortcutHandler {
        void foundShortcut(int fromNode, int toNode,
                           double existingDirectWeight, double distance,
                           int outgoingEdge, int outgoingEdgeOrigCount,
                           int incomingEdge, int incomingEdgeOrigCount);

        int getNode();
    }

    class CalcShortcutHandler implements ShortcutHandler {
        int node;
        CalcShortcutsResult calcShortcutsResult = new CalcShortcutsResult();

        @Override
        public int getNode() {
            return node;
        }

        public CalcShortcutHandler setNode(int node) {
            this.node = node;
            calcShortcutsResult.originalEdgesCount = 0;
            calcShortcutsResult.shortcutsCount = 0;
            return this;
        }

        @Override
        public void foundShortcut(int fromNode, int toNode,
                                  double existingDirectWeight, double distance,
                                  int outgoingEdge, int outgoingEdgeOrigCount,
                                  int incomingEdge, int incomingEdgeOrigCount) {
            calcShortcutsResult.shortcutsCount++;
            calcShortcutsResult.originalEdgesCount += incomingEdgeOrigCount + outgoingEdgeOrigCount;
        }
    }

    class AddShortcutHandler implements ShortcutHandler {
        int node;

        @Override
        public int getNode() {
            return node;
        }

        public AddShortcutHandler setNode(int node) {
            shortcuts.clear();
            this.node = node;
            return this;
        }

        @Override
        public void foundShortcut(int fromNode, int toNode,
                                  double existingDirectWeight, double existingDistSum,
                                  int outgoingEdge, int outgoingEdgeOrigCount,
                                  int incomingEdge, int incomingEdgeOrigCount) {
            // FOUND shortcut
            // but be sure that it is the only shortcut in the collection
            // and also in the graph for u->w. If existing AND identical weight => update setProperties.
            // Hint: shortcuts are always one-way due to distinct level of every node but we don't
            // know yet the levels so we need to determine the correct direction or if both directions
            Shortcut sc = new Shortcut(fromNode, toNode, existingDirectWeight, existingDistSum);
            if (shortcuts.containsKey(sc))
                return;

            Shortcut tmpSc = new Shortcut(toNode, fromNode, existingDirectWeight, existingDistSum);
            Shortcut tmpRetSc = shortcuts.get(tmpSc);
            // overwrite flags only if skipped edges are identical
            if (tmpRetSc != null && tmpRetSc.skippedEdge2 == incomingEdge && tmpRetSc.skippedEdge1 == outgoingEdge) {
                tmpRetSc.flags = PrepareEncoder.getScDirMask();
                return;
            }

            Shortcut old = shortcuts.put(sc, sc);
            if (old != null)
                throw new IllegalStateException("Shortcut did not exist (" + sc + ") but was overwriting another one? " + old);

            sc.skippedEdge1 = incomingEdge;
            sc.skippedEdge2 = outgoingEdge;
            sc.originalEdges = incomingEdgeOrigCount + outgoingEdgeOrigCount;
        }
    }

    public static class CalcShortcutsResult {
        public int originalEdgesCount;
        public int shortcutsCount;
    }
}