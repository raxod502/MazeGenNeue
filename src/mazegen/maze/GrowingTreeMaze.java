// Copyright (c) 2016 by Radon Rosborough. All rights reserved.
package mazegen.maze;

import mazegen.util.*;

/**
 * See http://weblog.jamisbuck.org/2011/1/27/maze-generation-growing-tree-algorithm
 */
public class GrowingTreeMaze extends ArrayMaze implements ReversibleGeneratingMaze {

    public enum State {
        PLACE_ROOT, GROW_TREE, PLACE_ENTRANCE_AND_EXIT, FINISHED;
    }

    public enum SelectionAlgorithm {
        RANDOM, FIRST, LAST, MIDDLE;
    }

    public interface Selector {
        int select(int size, ReversibleRandom random);
    }

    public static class SingleSelector implements Selector {

        private final SelectionAlgorithm selector;

        public SingleSelector(SelectionAlgorithm selector) {
            Require.nonNull(selector, "selector");
            this.selector = selector;
        }

        @Override
        public int select(int size, ReversibleRandom random) {
            switch (selector) {
                case RANDOM: return random.nextInt(size);
                case FIRST: return 0;
                case LAST: return size - 1;
                case MIDDLE: return size / 2;
                default: throw new AssertionError();
            }
        }

    }

    public static class DoubleSelector implements Selector {

        private final SingleSelector primarySelector, secondarySelector;
        private final double primaryChance;

        public DoubleSelector(SelectionAlgorithm primarySelector, SelectionAlgorithm secondarySelector, double primaryChance) {
            Require.nonNull(primarySelector, "primarySelector");
            Require.nonNull(secondarySelector, "secondarySelector");
            Require.between(primaryChance, 0, 1, "primaryChance");
            this.primarySelector = new SingleSelector(primarySelector);
            this.secondarySelector = new SingleSelector(secondarySelector);
            this.primaryChance = primaryChance;
        }

        @Override
        public int select(int size, ReversibleRandom random) {
            if (random.nextDouble() < primaryChance) {
                return primarySelector.select(size, random);
            }
            else {
                return secondarySelector.select(size, random);
            }
        }

    }

    public static class MultiSelector implements Selector {

        private final SingleSelector[] selectors;
        private final double[] weights;
        private final double totalWeight;

        public MultiSelector(SelectionAlgorithm[] selectors, double[] weights) {
            Require.nonEmpty(selectors, "selectors");
            Require.allNonNull(selectors, "selectors");
            Require.nonEmpty(weights, "weights");
            Require.allBetween(weights, 0, 1, "weights");
            Require.sameLength(selectors, weights, "selectors", "weights");
            this.selectors = new SingleSelector[selectors.length];
            for (int i=0; i<selectors.length; i++) {
                this.selectors[i] = new SingleSelector(selectors[i]);
            }
            this.weights = weights;
            double totalWeight = 0;
            for (double weight : weights) {
                totalWeight += weight;
            }
            this.totalWeight = totalWeight;
        }

        @Override
        public int select(int size, ReversibleRandom random) {
            double rand = random.nextDouble(totalWeight);
            int i = -1;
            int cumulativeWeight = 0;
            do {
                i += 1;
                cumulativeWeight += weights[i];
            }
            while (rand < cumulativeWeight);
            return i;
        }

    }

    public static class RecursiveBacktracker extends SingleSelector {

        public RecursiveBacktracker() {
            super(SelectionAlgorithm.LAST);
        }

    }

    public static class PrimAlgorithm extends SingleSelector {

        public PrimAlgorithm() {
            super(SelectionAlgorithm.LAST);
        }

    }

    public static class DefaultAlgorithm extends DoubleSelector {

        public DefaultAlgorithm() {
            this(0.5);
        }

        public DefaultAlgorithm(double primChance) {
            super(SelectionAlgorithm.RANDOM, SelectionAlgorithm.LAST, primChance);
        }

    }

    private final ReversibleRandom random;
    private final MazeCoordinate root;
    private MazeCoordinate entrance, exit;
    private final Selector selector;
    // Contains only cells that have been visited but not completed
    private final MyList<MazeCoordinate> visitedCells;
    // Contains true entries for cells that have been visited
    private final MultiDimensionalArray<Boolean> visitedCellMatrix;
    // Contains only cells that have been completed
    private final MyList<MazeCoordinate> completedCells;
    // The directions in which walls were dug, or null if no wall was dug
    private final MyList<Direction> pathDirections;
    private State state;
    // Counts cells that have not been visited OR completed
    private int remainingCells;

    public GrowingTreeMaze(int[] shape) {
        this(shape, new DefaultAlgorithm());
    }

    public GrowingTreeMaze(int[] shape, Selector selector) {
        this(shape, selector, System.nanoTime());
    }

    public GrowingTreeMaze(int[] shape, long seed) {
        this(shape, new DefaultAlgorithm(), seed);
    }

    public GrowingTreeMaze(int[] shape, Selector selector, long seed) {
        super(shape, true);
        if (getSize() == 1) {
            throw new IllegalArgumentException("maze must have more than one cell");
        }
        Require.nonNull(selector, "selector");
        random = new ReversibleRandom(seed);
        int[] indices = new int[shape.length];
        for (int i=0; i<shape.length; i++) {
            indices[i] = random.nextInt(shape[i]);
        }
        root = new MazeCoordinate(indices);
        this.selector = selector;
        visitedCells = new MyArrayList<>();
        visitedCellMatrix = new MultiDimensionalArray<>(shape, false);
        completedCells = new MyArrayList<>();
        pathDirections = new MyArrayList<>();
        state = State.PLACE_ROOT;
        remainingCells = getSize();
    }

    @Override
    public boolean isGenerationFinished() {
        return state == State.FINISHED;
    }

    private MazeCoordinate getMostDistantEdgeCell(MazeCoordinate fromCell) {
        // cells we need to visit
        MyList<MazeCoordinate> cells = new MyArrayList<>();
        // the directions we're visiting those cells from (to avoid backtracking)
        MyList<Direction> fromDirections = new MyArrayList<>();
        // the distances of those cells from the starting cell
        MyList<Integer> distances = new MyArrayList<>();
        cells.add(fromCell);
        fromDirections.add(null);
        distances.add(0);
        // the edge cell the farthest yet from the starting cell
        MazeCoordinate toCell = null;
        int greatestDistance = 0;
        while (!cells.isEmpty()) {
            MazeCoordinate cell = cells.remove();
            Direction fromDirection = fromDirections.remove();
            int distance = distances.remove();
            // found an edge cell farther from the starting cell than the last one
            if (distance > greatestDistance && isEdgeCell(cell)) {
                toCell = cell;
                greatestDistance = distance;
            }
            // check each direction
            for (Direction toDirection : Direction.getAllDirections(getDimensionCount())) {
                // don't backtrack, and don't cut through walls
                if (!toDirection.equals(fromDirection) && !hasWall(new MazeFace(cell, toDirection))) {
                    cells.add(cell.offset(toDirection));
                    // the direction we're coming from is the opposite of the direction we're
                    // going in
                    fromDirections.add(toDirection.invert());
                    distances.add(distance + 1);
                }
            }
        }
        return toCell;
    }

    private void setEntranceAndExit() {
        MazeCoordinate origin = MazeCoordinate.getOrigin(getDimensionCount());
        entrance = getMostDistantEdgeCell(origin);
        exit = getMostDistantEdgeCell(entrance);
    }

    private void unsetEntranceAndExit() {
        entrance = null;
        exit = null;
    }

    @Override
    public void advanceGeneration() {
        if (state != State.FINISHED) {
            random.advanceGenerator();
        }
        switch (state) {
            case PLACE_ROOT:
                visitedCells.add(root);
                visitedCellMatrix.set(root.getCoordinates(), true);
                remainingCells -= 1;
                state = State.GROW_TREE;
                break;
            case GROW_TREE:
                // pick a random visited cell
                int cellIndex = selector.select(visitedCells.size(), random);
                MazeCoordinate cell = visitedCells.get(cellIndex);
                // find unvisited neighbors of that cell
                MyList<MazeCoordinate> neighbors = new MyArrayList<>();
                MyList<Direction> directions = new MyArrayList<>();
                for (Direction direction : Direction.getAllDirections(getDimensionCount())) {
                    MazeCoordinate neighbor = cell.offset(direction);
                    try {
                        if (!visitedCellMatrix.get(neighbor.getCoordinates())) {
                            neighbors.add(neighbor);
                            directions.add(direction);
                        }
                    }
                    catch (IndexOutOfBoundsException e) {
                        // who cares?
                    }
                }
                // does it have any unvisited neighbors?
                if (!neighbors.isEmpty()) {
                    // if so, visit one
                    int neighborIndex = random.nextInt(neighbors.size());
                    MazeCoordinate neighbor = neighbors.get(neighborIndex);
                    Direction direction = directions.get(neighborIndex);
                    removeWall(new MazeFace(cell, direction));
                    visitedCells.add(neighbor);
                    visitedCellMatrix.set(neighbor.getCoordinates(), true);
                    pathDirections.add(directions.get(neighborIndex));
                    remainingCells -= 1;
                }
                else {
                    // otherwise, mark this cell as completed
                    visitedCells.remove(cellIndex);
                    completedCells.add(cell);
                    pathDirections.add(null);
                }
                if (remainingCells == 0) {
                    state = State.PLACE_ENTRANCE_AND_EXIT;
                }
                break;
            case PLACE_ENTRANCE_AND_EXIT:
                setEntranceAndExit();
                removeWall(getExternalFace(entrance));
                removeWall(getExternalFace(exit));
                state = State.FINISHED;
                break;
            case FINISHED:
                break;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public void resetGeneration() {
        random.resetGenerator();
        unsetEntranceAndExit();
        visitedCells.clear();
        visitedCellMatrix.fill(false);
        state = State.PLACE_ROOT;
        remainingCells = getSize();
    }

    @Override
    public void reverseGeneration() {
        if (state != State.PLACE_ROOT) {
            random.reverseGenerator();
        }
        switch (state) {
            case FINISHED:
                addWall(getExternalFace(entrance));
                addWall(getExternalFace(exit));
                unsetEntranceAndExit();
                state = State.PLACE_ENTRANCE_AND_EXIT;
                break;
            case PLACE_ENTRANCE_AND_EXIT:
            case GROW_TREE:
                if (!pathDirections.isEmpty()) {
                    Direction direction = pathDirections.remove();
                    boolean hasNeighbors = direction != null;
                    if (hasNeighbors) {
                        MazeCoordinate neighbor = visitedCells.remove();
                        addWall(new MazeFace(neighbor, direction.invert()));
                        visitedCellMatrix.set(neighbor.getCoordinates(), false);
                        remainingCells += 1;
                    }
                    else {
                        int cellIndex = selector.select(visitedCells.size() + 1, random);
                        visitedCells.add(cellIndex, completedCells.remove());
                    }
                    state = State.GROW_TREE;
                }
                else {
                    visitedCells.remove(0);
                    visitedCellMatrix.set(root.getCoordinates(), false);
                    remainingCells += 1;
                    state = State.PLACE_ROOT;
                }
                break;
            case PLACE_ROOT:
                break;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public String getState() {
        switch (state) {
            case PLACE_ROOT: return "placing root";
            case GROW_TREE: return "growing tree";
            case PLACE_ENTRANCE_AND_EXIT: return "placing entrance and exit";
            case FINISHED: return "finished";
            default: throw new AssertionError();
        }
    }

}
