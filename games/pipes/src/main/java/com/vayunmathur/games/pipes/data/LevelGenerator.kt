package com.vayunmathur.games.pipes.data

import kotlin.random.Random

object LevelGenerator {

    fun rectangularCells(rows: Int, cols: Int): Set<CellPos> {
        return buildSet {
            for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
        }
    }

    fun octagonCells(size: Int, cut: Int = size / 4 + 1): Set<CellPos> {
        return buildSet {
            for (r in 0 until size) for (c in 0 until size) {
                val topLeft = r < cut && c < cut - r
                val topRight = r < cut && c >= size - cut + r
                val botLeft = r >= size - cut && c < cut - (size - 1 - r)
                val botRight = r >= size - cut && c >= size - cut + (size - 1 - r)
                if (!(topLeft || topRight || botLeft || botRight)) {
                    add(CellPos(r, c))
                }
            }
        }
    }

    fun spiralCells(rows: Int, cols: Int): Set<CellPos> {
        val result = mutableSetOf<CellPos>()
        var top = 0; var bottom = rows - 1; var left = 0; var right = cols - 1
        val total = rows * cols
        val target = (total * 0.7).toInt()
        while (result.size < target && top <= bottom && left <= right) {
            for (c in left..right) result.add(CellPos(top, c))
            top++
            for (r in top..bottom) result.add(CellPos(r, right))
            right--
            if (top <= bottom) {
                for (c in right downTo left) result.add(CellPos(bottom, c))
                bottom--
            }
            if (left <= right) {
                for (r in bottom downTo top) result.add(CellPos(r, left))
                left++
            }
        }
        return result
    }

    private fun computeAdjacency(cells: Set<CellPos>): Map<CellPos, List<CellPos>> {
        val dirs = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
        return cells.associateWith { cell ->
            dirs.mapNotNull { (dr, dc) ->
                val n = CellPos(cell.row + dr, cell.col + dc)
                if (n in cells) n else null
            }
        }
    }

    fun generateLevel(
        cells: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        numFlows: Int,
        seed: Long,
        id: String
    ): LevelData? {
        val random = Random(seed)
        val rows = cells.maxOf { it.row } + 1
        val cols = cells.maxOf { it.col } + 1

        for (attempt in 0 until 50) {
            val result = tryGenerate(cells, adjacency, numFlows, Random(seed + attempt))
            if (result != null) {
                val (paths, endpoints) = result
                return LevelData(
                    id = id,
                    rows = rows,
                    cols = cols,
                    cells = cells,
                    adjacency = adjacency,
                    renderPositions = null,
                    endpoints = endpoints,
                    optimalMoves = cells.size
                )
            }
        }
        return null
    }

    private fun tryGenerate(
        cells: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        numFlows: Int,
        random: Random
    ): Pair<List<List<CellPos>>, List<EndpointPair>>? {
        val unmarked = cells.toMutableSet()
        val paths = mutableListOf<List<CellPos>>()

        for (flowIndex in 0 until numFlows) {
            if (unmarked.isEmpty()) break

            val isLast = flowIndex == numFlows - 1
            val start = unmarked.random(random)
            val path = mutableListOf(start)
            unmarked.remove(start)

            val minLength = if (isLast) unmarked.size + 1 else 3
            val maxLength = if (isLast) unmarked.size + 1 else unmarked.size - (numFlows - flowIndex - 1) * 2

            while (path.size < maxLength) {
                val current = path.last()
                val neighbors = (adjacency[current] ?: emptyList())
                    .filter { it in unmarked }
                    .shuffled(random)

                val validNeighbors = neighbors.filter { candidate ->
                    val remaining = unmarked.toMutableSet()
                    remaining.remove(candidate)
                    isStillConnected(remaining, adjacency)
                }

                if (validNeighbors.isEmpty()) break
                val next = validNeighbors.first()
                path.add(next)
                unmarked.remove(next)

                if (!isLast && path.size >= minLength && random.nextFloat() < 0.3f) break
            }

            if (path.size < 2) return null
            paths.add(path)
        }

        if (unmarked.isNotEmpty()) return null

        val endpoints = paths.mapIndexed { index, path ->
            EndpointPair(index, listOf(path.first(), path.last()))
        }
        return paths to endpoints
    }

    private fun isStillConnected(cells: Set<CellPos>, adjacency: Map<CellPos, List<CellPos>>): Boolean {
        if (cells.size <= 1) return true
        val start = cells.first()
        val visited = mutableSetOf(start)
        val queue = ArrayDeque<CellPos>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in adjacency[current] ?: emptyList()) {
                if (neighbor in cells && neighbor !in visited) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }
        return visited.size == cells.size
    }

    fun generatePack(
        name: String,
        shape: String,
        cells: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        levelCount: Int,
        flowRange: IntRange,
        seed: Long
    ): List<LevelData> {
        val levels = mutableListOf<LevelData>()
        var currentSeed = seed
        for (i in 0 until levelCount) {
            val numFlows = flowRange.random(Random(currentSeed))
            val id = "${name.replace("×", "x").replace(" ", "_")}_${String.format("%03d", i + 1)}"
            val level = generateLevel(cells, adjacency, numFlows, currentSeed, id)
            if (level != null) {
                levels.add(level)
            }
            currentSeed += 100
        }
        return levels
    }
}
