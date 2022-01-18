/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.randomcutforest.tree;

import static com.amazon.randomcutforest.CommonUtils.checkArgument;
import static com.amazon.randomcutforest.CommonUtils.toFloatArray;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Stack;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.amazon.randomcutforest.MultiVisitor;
import com.amazon.randomcutforest.Visitor;
import com.amazon.randomcutforest.store.IPointStoreView;
import com.amazon.randomcutforest.store.IndexIntervalManager;

/**
 * A fixed-size buffer for storing interior tree nodes. An interior node is
 * defined by its location in the tree (parent and child nodes), its random cut,
 * and its bounding box. The NodeStore class uses arrays to store these field
 * values for a collection of nodes. An index in the store can be used to look
 * up the field values for a particular node.
 *
 * The internal nodes (handled by this store) corresponds to
 * [0..upperRangeLimit]
 *
 * If we think of an array of Node objects as being row-oriented (where each row
 * is a Node), then this class is analogous to a column-oriented database of
 * Nodes.
 *
 * Note that a NodeStore does not store instances of the
 * {@link com.amazon.randomcutforest.tree.Node} class.
 */
public abstract class AbstractNodeStore {

    public static double SWITCH_FRACTION = 0.499;

    public static int Null = -1;

    public static boolean DEFAULT_STORE_PARENT = false;

    /**
     * the number of internal nodes; the nodes will range from 0..capacity-1 the
     * value capacity would correspond to "not yet set" the values Y= capacity+1+X
     * correspond to pointstore index X note that capacity + 1 + X =
     * number_of_leaves + X
     */
    protected final int capacity;
    protected final int dimensions;
    protected final float[] cutValue;
    protected double boundingboxCacheFraction;
    protected IndexIntervalManager freeNodeManager;
    protected double[] rangeSumData;
    protected float[] boundingBoxData;
    protected final IPointStoreView<float[]> pointStoreView;
    protected final HashMap<Integer, Integer> leafMass;
    protected boolean centerOfMassEnabled;
    protected boolean storeSequenceIndexesEnabled;
    protected float[] pointSum;
    protected HashMap<Integer, HashMap<Long, Integer>> sequenceMap;

    public AbstractNodeStore(AbstractNodeStore.Builder<?> builder) {
        this.capacity = builder.capacity;
        this.dimensions = builder.dimensions;
        if ((builder.leftIndex == null)) {
            freeNodeManager = new IndexIntervalManager(capacity);
        }
        this.boundingboxCacheFraction = builder.boundingBoxCacheFraction;
        cutValue = (builder.cutValues != null) ? builder.cutValues : new float[capacity];
        leafMass = new HashMap<>();
        int cache_limit = (int) Math.floor(boundingboxCacheFraction * capacity);
        rangeSumData = new double[cache_limit];
        boundingBoxData = new float[2 * dimensions * cache_limit];
        this.pointStoreView = builder.pointStoreView;
        this.centerOfMassEnabled = builder.centerOfMassEnabled;
        this.storeSequenceIndexesEnabled = builder.storeSequencesEnabled;
        if (this.centerOfMassEnabled) {
            pointSum = new float[(capacity) * dimensions];
        }
        if (this.storeSequenceIndexesEnabled) {
            sequenceMap = new HashMap<>();
        }
    }

    protected abstract int addNode(Stack<int[]> pathToRoot, float[] point, long sequenceIndex, int pointIndex,
            int childIndex, int cutDimension, float cutValue, BoundingBoxFloat box);

    protected int addLeaf(int pointIndex, long sequenceIndex) {
        if (storeSequenceIndexesEnabled) {
            HashMap<Long, Integer> leafMap = sequenceMap.remove(pointIndex);
            if (leafMap == null) {
                leafMap = new HashMap<>();
            }
            Integer count = leafMap.remove(sequenceIndex);
            if (count != null) {
                leafMap.put(sequenceIndex, count + 1);
            } else {
                leafMap.put(sequenceIndex, 1);
            }
            sequenceMap.put(pointIndex, leafMap);
        }
        return pointIndex + capacity + 1;
    }

    public void removeLeaf(int leafPointIndex, long sequenceIndex) {
        HashMap<Long, Integer> leafMap = sequenceMap.remove(leafPointIndex);
        checkArgument(leafMap != null, " leaf index not found in tree");
        Integer count = leafMap.remove(sequenceIndex);
        checkArgument(count != null, " sequence index not found in leaf");
        if (count > 1) {
            leafMap.put(sequenceIndex, count - 1);
            sequenceMap.put(leafPointIndex, leafMap);
        } else if (leafMap.size() > 0) {
            sequenceMap.put(leafPointIndex, leafMap);
        }
    }

    public boolean isLeaf(int index) {
        return index > capacity;
    }

    public boolean isInternal(int index) {
        return index < capacity && index >= 0;
    }

    public abstract void addToPartialTree(Stack<int[]> pathToRoot, float[] point, int pointIndex);

    public abstract int getLeftIndex(int index);

    public abstract int getRightIndex(int index);

    public abstract void setRoot(int index);

    public float[] getPointSum(int index) {
        checkArgument(centerOfMassEnabled, " enable center of mass");
        return (isLeaf(index)) ? pointStoreView.getScaledPoint(getPointIndex(index), getMass(index))
                : Arrays.copyOfRange(pointSum, index * dimensions, (index + 1) * dimensions);
    }

    public void invalidatePointSum(int index) {
        for (int i = 0; i < dimensions; i++) {
            pointSum[index * dimensions + i] = 0;
        }
    }

    public void recomputePointSum(int index) {
        float[] left = getPointSum(getLeftIndex(index));
        float[] right = getPointSum(getRightIndex(index));
        for (int i = 0; i < dimensions; i++) {
            pointSum[index * dimensions + i] = left[i] + right[i];
        }
    }

    public void increaseLeafMass(int index) {
        int y = (index - capacity - 1);
        leafMass.merge(y, 1, Integer::sum);
    }

    public int decreaseLeafMass(int index) {
        int y = (index - capacity - 1);
        Integer value = leafMass.remove(y);
        if (value != null) {
            if (value > 1) {
                leafMass.put(y, (value - 1));
                return value;
            } else {
                return 1;
            }
        } else {
            return 0;
        }
    }

    public void resizeCache(double fraction) {
        if (fraction == 0) {
            rangeSumData = null;
            boundingBoxData = null;
        } else {
            int limit = (int) Math.floor(fraction * capacity);
            rangeSumData = Arrays.copyOf(rangeSumData, limit);
            boundingBoxData = Arrays.copyOf(boundingBoxData, limit * 2 * dimensions);
        }
        boundingboxCacheFraction = fraction;
    }

    public int translate(int index) {
        if (rangeSumData.length <= index) {
            return Integer.MAX_VALUE;
        } else {
            return index;
        }
    }

    void copyBoxToData(int idx, BoundingBoxFloat box) {
        int base = 2 * idx * dimensions;
        int mid = base + dimensions;
        System.arraycopy(box.getMinValues(), 0, boundingBoxData, base, dimensions);
        System.arraycopy(box.getMaxValues(), 0, boundingBoxData, mid, dimensions);
        rangeSumData[idx] = box.getRangeSum();
    }

    public boolean checkContainsAndAddPoint(int index, float[] point) {
        int idx = translate(index);
        if (idx != Integer.MAX_VALUE && rangeSumData[idx] != 0) {
            int base = 2 * idx * dimensions;
            int mid = base + dimensions;
            double rangeSum = 0;
            for (int i = 0; i < dimensions; i++) {
                boundingBoxData[base + i] = Math.min(boundingBoxData[base + i], point[i]);
            }
            for (int i = 0; i < dimensions; i++) {
                boundingBoxData[mid + i] = Math.max(boundingBoxData[mid + i], point[i]);
            }
            for (int i = 0; i < dimensions; i++) {
                rangeSum += boundingBoxData[mid + i] - boundingBoxData[base + i];
            }
            boolean answer = (rangeSumData[idx] == rangeSum);
            rangeSumData[idx] = rangeSum;
            return answer;
        }
        return false;
    }

    public BoundingBoxFloat getBox(int index) {
        if (isLeaf(index)) {
            float[] point = pointStoreView.get(getPointIndex(index));
            return new BoundingBoxFloat(point, point);
        } else {
            checkArgument(isInternal(index), " incomplete state");
            int idx = translate(index);
            if (idx != Integer.MAX_VALUE) {
                if (rangeSumData[idx] != 0) {
                    // return non-trivial boxes
                    return getBoxFromData(idx);
                } else {
                    BoundingBoxFloat box = reconstructBox(index, pointStoreView);
                    copyBoxToData(idx, box);
                    return box;
                }
            }
            return reconstructBox(index, pointStoreView);
        }
    }

    public BoundingBoxFloat reconstructBox(int index, IPointStoreView<float[]> pointStoreView) {
        BoundingBoxFloat mutatedBoundingBox = getBox(getLeftIndex(index));
        growNodeBox(mutatedBoundingBox, pointStoreView, index, getRightIndex(index));
        return mutatedBoundingBox;
    }

    boolean checkStrictlyContains(int index, float[] point) {
        int idx = translate(index);
        if (idx != Integer.MAX_VALUE) {
            int base = 2 * idx * dimensions;
            int mid = base + dimensions;
            boolean isInside = true;
            for (int i = 0; i < dimensions && isInside; i++) {
                if (point[i] >= boundingBoxData[mid + i] || boundingBoxData[base + i] >= point[i]) {
                    isInside = false;
                }
            }
            return isInside;
        }
        return false;
    }

    public boolean checkContainsAndRebuildBox(int index, float[] point, IPointStoreView<float[]> pointStoreView) {
        int idx = translate(index);
        if (idx != Integer.MAX_VALUE && rangeSumData[idx] != 0) {
            if (!checkStrictlyContains(index, point)) {
                BoundingBoxFloat mutatedBoundingBox = reconstructBox(index, pointStoreView);
                copyBoxToData(idx, mutatedBoundingBox);
                return false;
            }
            return true;
        }
        return false;
    }

    public BoundingBoxFloat getBoxFromData(int idx) {
        int base = 2 * idx * dimensions;
        int mid = base + dimensions;

        return new BoundingBoxFloat(Arrays.copyOfRange(boundingBoxData, base, base + dimensions),
                Arrays.copyOfRange(boundingBoxData, mid, mid + dimensions));
    }

    protected void addBox(int index, float[] point, BoundingBoxFloat box) {
        if (isInternal(index)) {
            int idx = translate(index);
            if (idx != Integer.MAX_VALUE) { // always add irrespective of rangesum
                copyBoxToData(idx, box);
                checkContainsAndAddPoint(index, point);
            }
        }
    }

    public void growNodeBox(BoundingBoxFloat box, IPointStoreView<float[]> pointStoreView, int node, int sibling) {
        if (isLeaf(sibling)) {
            float[] point = pointStoreView.get(getPointIndex(sibling));
            box.addPoint(point);
        } else {
            checkArgument(isInternal(sibling), " incomplete state " + sibling);
            int siblingIdx = translate(sibling);
            if (siblingIdx != Integer.MAX_VALUE) {
                if (rangeSumData[siblingIdx] != 0) {
                    box.addBox(getBoxFromData(siblingIdx));
                } else {
                    BoundingBoxFloat newBox = getBox(siblingIdx);
                    copyBoxToData(siblingIdx, newBox);
                    box.addBox(newBox);
                }
                return;
            }
            growNodeBox(box, pointStoreView, sibling, getLeftIndex(sibling));
            growNodeBox(box, pointStoreView, sibling, getRightIndex(sibling));
            return;
        }
    }

    public double probabilityOfCut(int node, float[] point, IPointStoreView<float[]> pointStoreView,
            BoundingBoxFloat otherBox) {
        int nodeIdx = translate(node);
        if (nodeIdx != Integer.MAX_VALUE && rangeSumData[nodeIdx] != 0) {
            int base = 2 * nodeIdx * dimensions;
            int mid = base + dimensions;
            double minsum = 0;
            double maxsum = 0;
            for (int i = 0; i < dimensions; i++) {
                minsum += Math.max(boundingBoxData[base + i] - point[i], 0);
            }
            for (int i = 0; i < dimensions; i++) {
                maxsum += Math.max(point[i] - boundingBoxData[mid + i], 0);
            }
            double sum = maxsum + minsum;

            if (sum == 0.0) {
                return 0.0;
            }
            return sum / (rangeSumData[nodeIdx] + sum);
        } else if (otherBox != null) {
            return otherBox.probabilityOfCut(point);
        } else {
            BoundingBoxFloat box = getBox(node);
            return box.probabilityOfCut(point);
        }
    }

    protected abstract void decreaseMassOfInternalNode(int node);

    protected abstract void increaseMassOfInternalNode(int node);

    protected void manageAncestorsAdd(Stack<int[]> path, float[] point, IPointStoreView<float[]> pointStoreview) {
        while (!path.isEmpty()) {
            int index = path.pop()[0];
            increaseMassOfInternalNode(index);
            if (pointSum != null) {
                recomputePointSum(index);
            }
            if (boundingboxCacheFraction > 0.0) {
                checkContainsAndRebuildBox(index, point, pointStoreview);
                checkContainsAndAddPoint(index, point);
            }
        }
    }

    protected void manageAncestorsDelete(Stack<int[]> path, float[] point, IPointStoreView<float[]> pointStoreview) {
        boolean resolved = false;
        while (!path.isEmpty()) {
            int index = path.pop()[0];
            decreaseMassOfInternalNode(index);
            if (pointSum != null) {
                recomputePointSum(index);
            }
            if (boundingboxCacheFraction > 0.0 && !resolved) {
                resolved = checkContainsAndRebuildBox(index, point, pointStoreview);
            }
        }
    }

    public Stack<int[]> getPath(int root, float[] point, boolean verbose) {
        int node = root;
        Stack<int[]> answer = new Stack<>();
        answer.push(new int[] { root, capacity });
        while (isInternal(node)) {
            double y = getCutValue(node);
            if (leftOf(node, point)) {
                answer.push(new int[] { getLeftIndex(node), getRightIndex(node) });
                node = getLeftIndex(node);
            } else { // this would push potential Null, of node == capacity
                     // that would be used for tree reconstruction
                answer.push(new int[] { getRightIndex(node), getLeftIndex(node) });
                node = getRightIndex(node);
            }
        }
        return answer;
    }

    public abstract void deleteInternalNode(int index);

    public int getLeafMass(int index) {
        int y = (index - capacity - 1);
        Integer value = leafMass.get(y);
        if (value != null) {
            return value + 1;
        } else {
            return 1;
        }
    }

    public abstract int getMass(int index);

    public int getPointIndex(int index) {
        return index - capacity - 1;
    }

    protected boolean leftOf(float cutValue, int cutDimension, float[] point) {
        return point[cutDimension] <= cutValue;
    }

    public boolean leftOf(int node, float[] point) {
        int cutDimension = getCutDimension(node);
        return leftOf(cutValue[node], cutDimension, point);
    }

    public int getSibling(int node, int parent) {
        int sibling = getLeftIndex(parent);
        if (node == sibling) {
            sibling = getRightIndex(parent);
        }
        return sibling;
    }

    public abstract void spliceEdge(int parent, int node, int newNode);

    public abstract void replaceParentBySibling(int grandParent, int parent, int node);

    public double dynamicScore(int root, int ignoreMass, double[] point, IPointStoreView<float[]> pointStoreView,
            BiFunction<Double, Double, Double> scoreSeen, BiFunction<Double, Double, Double> scoreUnseen,
            Function<Double, Double> treeDamp) {
        if (root == Null) {
            return 0.0;
        }
        BoundingBoxFloat boundingBox = null;
        if (boundingboxCacheFraction < SWITCH_FRACTION || ignoreMass > 0) {
            float[] fakePoint = new float[point.length];
            boundingBox = new BoundingBoxFloat(fakePoint, fakePoint);
        }
        return scoreScalar(root, 0, boundingBox, ignoreMass, toFloatArray(point), pointStoreView, scoreSeen,
                scoreUnseen, treeDamp)[1];
    }

    public double[] scoreScalar(int node, int depth, BoundingBoxFloat box, int ignoreMass, float[] point,
            IPointStoreView<float[]> pointStoreView, BiFunction<Double, Double, Double> scoreSeen,
            BiFunction<Double, Double, Double> scoreUnseen, Function<Double, Double> treeDamp) {
        if (isLeaf(node)) {
            double mass = getMass(node);
            float[] oldPoint = pointStoreView.get(getPointIndex(node));
            int ignoreFlag = (mass > ignoreMass) ? 1 : 0;
            if (box != null) {
                box.replaceBox(oldPoint);
            }
            if (Arrays.equals(point, oldPoint) && ignoreFlag == 1) {
                return new double[] { 0.0, treeDamp.apply(mass) * scoreSeen.apply(depth * 1.0, mass), ignoreFlag };
            } else {
                return new double[] { 1.0, scoreUnseen.apply(1.0 * depth, mass), ignoreFlag };
            }
        }
        checkArgument(isInternal(node), " incomplete state" + depth + " " + node);
        double[] answer;
        if (leftOf(node, point)) {
            answer = scoreScalar(getLeftIndex(node), depth + 1, box, ignoreMass, point, pointStoreView, scoreSeen,
                    scoreUnseen, treeDamp);
            if (answer[0] != 0.0 && box != null) {
                if (answer[2] == 1) {
                    growNodeBox(box, pointStoreView, node, getRightIndex(node));
                } else {
                    box.copyFrom(getBox(getRightIndex(node)));
                    answer[2] = 1;
                }
            }
        } else {
            answer = scoreScalar(getRightIndex(node), depth + 1, box, ignoreMass, point, pointStoreView, scoreSeen,
                    scoreUnseen, treeDamp);
            if (answer[0] != 0.0 && box != null) {
                if (answer[2] == 1) {
                    growNodeBox(box, pointStoreView, node, getLeftIndex(node));
                } else {
                    box.copyFrom(getBox(getLeftIndex(node)));
                    answer[2] = 1;
                }
            }
        }

        if (answer[0] == 0.0) {
            return answer;
        }

        double prob = (ignoreMass == 0) ? probabilityOfCut(node, point, pointStoreView, box)
                : box.probabilityOfCut(point);
        answer[0] = prob;
        answer[1] = answer[1] * (1.0 - prob) + prob * scoreUnseen.apply(1.0 * depth, 1.0 * getMass(node));
        return answer;
    }

    public abstract int getCutDimension(int index);

    public double getCutValue(int index) {
        return cutValue[index];
    }

    public double getBoundingboxCacheFraction() {
        return boundingboxCacheFraction;
    }

    protected <R> void traversePathToLeafAndVisitNodes(double[] point, Visitor<R> visitor, int root,
            IPointStoreView<float[]> pointStoreView, Function<double[], double[]> projectToTree) {
        NodeView currentNodeView = new NodeView(this, pointStoreView, root);
        traversePathToLeafAndVisitNodes(point, visitor, currentNodeView, root, 0);
    }

    protected boolean toLeft(double[] point, int currentNodeOffset) {
        return point[getCutDimension(currentNodeOffset)] <= cutValue[currentNodeOffset];
    }

    BoundingBoxFloat getLeftBox(int index) {
        return getBox(getLeftIndex(index));
    }

    BoundingBoxFloat getRightBox(int index) {
        return getBox(getRightIndex(index));
    }

    protected <R> void traversePathToLeafAndVisitNodes(double[] point, Visitor<R> visitor, NodeView currentNodeView,
            int node, int depthOfNode) {
        if (isLeaf(node)) {
            currentNodeView.setCurrentNode(node, getPointIndex(node), false);
            visitor.acceptLeaf(currentNodeView, depthOfNode);
        } else {
            checkArgument(isInternal(node), " incomplete state " + node + " " + depthOfNode);
            if (toLeft(point, node)) {
                traversePathToLeafAndVisitNodes(point, visitor, currentNodeView, getLeftIndex(node), depthOfNode + 1);
                currentNodeView.updateToParent(node, getRightIndex(node), false);
            } else {
                traversePathToLeafAndVisitNodes(point, visitor, currentNodeView, getRightIndex(node), depthOfNode + 1);
                currentNodeView.updateToParent(node, getLeftIndex(node), false);
            }
            visitor.accept(currentNodeView, depthOfNode);
        }
    }

    protected <R> void traverseTreeMulti(double[] point, MultiVisitor<R> visitor, int root,
            IPointStoreView<float[]> pointStoreView, Function<double[], double[]> liftToTree) {
        NodeView currentNodeView = new NodeView(this, pointStoreView, root);
        traverseTreeMulti(point, visitor, currentNodeView, root, 0);
    }

    protected <R> void traverseTreeMulti(double[] point, MultiVisitor<R> visitor, NodeView currentNodeView, int node,
            int depthOfNode) {
        if (isLeaf(node)) {
            currentNodeView.setCurrentNode(node, getPointIndex(node), false);
            visitor.acceptLeaf(currentNodeView, depthOfNode);
        } else {
            checkArgument(isInternal(node), " incomplete state");
            currentNodeView.setCurrentNodeOnly(node);
            if (visitor.trigger(currentNodeView)) {
                traverseTreeMulti(point, visitor, currentNodeView, getLeftIndex(node), depthOfNode + 1);
                MultiVisitor<R> newVisitor = visitor.newCopy();
                currentNodeView.setCurrentNodeOnly(getRightIndex(node));
                traverseTreeMulti(point, newVisitor, currentNodeView, getRightIndex(node), depthOfNode + 1);
                currentNodeView.updateToParent(node, getLeftIndex(node), false);
                visitor.combine(newVisitor);
            } else if (toLeft(point, node)) {
                traverseTreeMulti(point, visitor, currentNodeView, getLeftIndex(node), depthOfNode + 1);
                currentNodeView.updateToParent(node, getRightIndex(node), false);
            } else {
                traverseTreeMulti(point, visitor, currentNodeView, getRightIndex(node), depthOfNode + 1);
                currentNodeView.updateToParent(node, getLeftIndex(node), false);
            }
            visitor.accept(currentNodeView, depthOfNode);
        }
    }

    public abstract int[] getCutDimension();

    public abstract int[] getRightIndex();

    public abstract int[] getLeftIndex();

    public float[] getCutValues() {
        return cutValue;
    }

    public int getCapacity() {
        return capacity;
    }

    public boolean isCanonicalAndNotALeaf() {
        return false;
    }

    public int size() {
        return capacity - freeNodeManager.size();
    }

    /**
     * a builder
     */

    public static class Builder<T extends Builder<T>> {
        protected int dimensions;
        protected int capacity;
        protected int[] leftIndex;
        protected int[] rightIndex;
        protected int[] cutDimension;
        protected float[] cutValues;
        protected int root;
        protected double boundingBoxCacheFraction;
        protected boolean centerOfMassEnabled;
        protected boolean storeSequencesEnabled;
        protected boolean storeParent = DEFAULT_STORE_PARENT;
        protected IPointStoreView<float[]> pointStoreView;

        // dimension of the points being stored
        public T dimensions(int dimensions) {
            this.dimensions = dimensions;
            return (T) this;
        }

        // maximum number of points in the store
        public T capacity(int capacity) {
            this.capacity = capacity;
            return (T) this;
        }

        public T useRoot(int root) {
            this.root = root;
            return (T) this;
        }

        public T leftIndex(int[] leftIndex) {
            this.leftIndex = leftIndex;
            return (T) this;
        }

        public T rightIndex(int[] rightIndex) {
            this.rightIndex = rightIndex;
            return (T) this;
        }

        public T cutDimension(int[] cutDimension) {
            this.cutDimension = cutDimension;
            return (T) this;
        }

        public T cutValues(float[] cutValues) {
            this.cutValues = cutValues;
            return (T) this;
        }

        public T pointStoreView(IPointStoreView<float[]> pointStoreView) {
            this.pointStoreView = pointStoreView;
            return (T) this;
        }

        public T boundingBoxCacheFraction(double boundingBoxCacheFraction) {
            this.boundingBoxCacheFraction = boundingBoxCacheFraction;
            return (T) this;
        }

        public T centerOfMassEnabled(boolean centerOfMassEnabled) {
            this.centerOfMassEnabled = centerOfMassEnabled;
            return (T) this;
        }

        public T storeParent(boolean storeParent) {
            this.storeParent = storeParent;
            return (T) this;
        }

        public T storeSequencesEnabled(boolean storeSequencesEnabled) {
            this.storeSequencesEnabled = storeSequencesEnabled;
            return (T) this;
        }

        public AbstractNodeStore build() {
            checkArgument(pointStoreView != null, " a point store view is required ");
            if (leftIndex == null) {
                checkArgument(rightIndex == null, " incorrect option of right indices");
                checkArgument(cutValues == null, "incorrect option of cut values");
                checkArgument(cutDimension == null, " incorrect option of cut dimensions");
            } else {
                checkArgument(leftIndex.length == capacity, " incorrect length of right indices");
                checkArgument(cutValues.length == capacity, "incorrect length of cut values");
                checkArgument(cutDimension.length == capacity, " incorrect length of cut dimensions");
            }

            // capacity is numbner of internal nodes
            if (capacity < 256 && pointStoreView.getDimensions() <= 256) {
                return new NodeStoreSmall(this);
            } else if (capacity < Character.MAX_VALUE && pointStoreView.getDimensions() <= Character.MAX_VALUE) {
                return new NodeStoreMedium(this);
            } else {
                return new NodeStoreLarge(this);
            }
        }

    }

    public static Builder builder() {
        return new Builder();
    }

}
