package com.vayunmathur.games.pipes.data

import kotlin.random.Random

object LevelGenerator {

    fun rectangularCells(rows: Int, cols: Int): Set<CellPos> {
        return buildSet {
            for (r in 0 until rows) for (c in 0 until cols) add(CellPos(r, c))
        }
    }

    fun generateLevel(
        cells: Set<CellPos>,
        adjacency: Map<CellPos, List<CellPos>>,
        numFlows: Int,
        seed: Long,
        id: String
    ): LevelData? {
        val rows = cells.maxOf { it.row } + 1
        val cols = cells.maxOf { it.col } + 1

        for (attempt in 0 until 50) {
            val (_, endpoints) = tryGenerate(cells, adjacency, numFlows, Random(seed + attempt)) ?: continue
            return LevelData(
                id = id,
                rows = rows,
                cols = cols,
                cells = cells,
                adjacency = adjacency,
                renderPositions = null,
                endpoints = endpoints,
                bridges = emptySet(),
                optimalMoves = cells.size
            )
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
    ): List<LevelData> = (0 until levelCount).mapNotNull { i ->
        val currentSeed = seed + i * 100
        val numFlows = flowRange.random(Random(currentSeed))
        val id = "${name.replace("×", "x").replace(" ", "_")}_${String.format("%03d", i + 1)}"
        generateLevel(cells, adjacency, numFlows, currentSeed, id)
    }
}
