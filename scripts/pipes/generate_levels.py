#!/usr/bin/env python3
"""Generate Flow/Pipes levels.

Rectangular grids: Numberlink algorithm (Thomas Ahle) — paths fill the grid
by construction. Levels validated with NumberLink solver (unique solution).
"""
import sys, json, random, subprocess, os
from collections import defaultdict


# ===================== Numberlink Generator =====================

T, L, R = range(3)


def sign(x):
    if x == 0:
        return x
    return -1 if x < 0 else 1


def unrotate(x, y, dx, dy):
    while (dx, dy) != (0, 1):
        x, y, dx, dy = -y, x, -dy, dx
    return x, y


class Path:
    def __init__(self, steps):
        self.steps = steps

    def xys(self, dx=0, dy=1):
        x, y = 0, 0
        yield (x, y)
        for step in self.steps:
            x, y = x + dx, y + dy
            yield (x, y)
            if step == L:
                dx, dy = -dy, dx
            if step == R:
                dx, dy = dy, -dx
            elif step == T:
                x, y = x + dx, y + dy
                yield (x, y)

    def test(self):
        ps = list(self.xys())
        return len(set(ps)) == len(ps)

    def test_loop(self):
        ps = list(self.xys())
        seen = set(ps)
        return len(ps) == len(seen) or (len(ps) == len(seen) + 1 and ps[0] == ps[-1])

    def winding(self):
        return self.steps.count(R) - self.steps.count(L)


class UnionFind:
    def __init__(self):
        self.uf = {}

    def union(self, a, b):
        self.uf[self.find(a)] = self.find(b)

    def find(self, a):
        if self.uf.get(a, a) == a:
            return a
        par = self.find(self.uf.get(a, a))
        self.uf[a] = par
        return par


class Mitm:
    def __init__(self, lr_price=2, t_price=1):
        self.lr_price = lr_price
        self.t_price = t_price
        self.inv = defaultdict(list)
        self.list = []

    def prepare(self, budget):
        for path, (x, y, dx, dy) in self._good_paths(0, 0, 0, 1, budget):
            self.list.append((path, x, y, dx, dy))
            self.inv[x, y, dx, dy].append(path)

    def rand_path2(self, xn, yn, dxn, dyn):
        seen = set()
        path = []
        for _attempt in range(10000):
            seen.clear()
            del path[:]
            x, y, dx, dy = 0, 0, 0, 1
            seen.add((x, y))
            for _ in range(2 * (abs(xn) + abs(yn))):
                step, = random.choices(
                    [L, R, T],
                    [1 / self.lr_price, 1 / self.lr_price, 2 / self.t_price])
                path.append(step)
                x, y = x + dx, y + dy
                if (x, y) in seen:
                    break
                seen.add((x, y))
                if step == L:
                    dx, dy = -dy, dx
                if step == R:
                    dx, dy = dy, -dx
                elif step == T:
                    x, y = x + dx, y + dy
                    if (x, y) in seen:
                        break
                    seen.add((x, y))
                if (x, y) == (xn, yn):
                    return Path(path)
                ends = self._lookup(dx, dy, xn - x, yn - y, dxn, dyn)
                if ends:
                    return Path(tuple(path) + random.choice(ends))
        return None

    def rand_loop(self, clock=0):
        for _attempt in range(10000):
            path, x, y, dx, dy = random.choice(self.list)
            path2s = self._lookup(dx, dy, -x, -y, 0, 1)
            if path2s:
                path2 = random.choice(path2s)
                joined = Path(path + path2)
                if clock and joined.winding() != clock * 4:
                    continue
                if joined.test_loop():
                    return joined
        return None

    def _good_paths(self, x, y, dx, dy, budget, seen=None):
        if seen is None:
            seen = set()
        if budget >= 0:
            yield (), (x, y, dx, dy)
        if budget <= 0:
            return
        seen.add((x, y))
        x1, y1 = x + dx, y + dy
        if (x1, y1) not in seen:
            for path, end in self._good_paths(
                    x1, y1, -dy, dx, budget - self.lr_price, seen):
                yield (L,) + path, end
            for path, end in self._good_paths(
                    x1, y1, dy, -dx, budget - self.lr_price, seen):
                yield (R,) + path, end
            seen.add((x1, y1))
            x2, y2 = x1 + dx, y1 + dy
            if (x2, y2) not in seen:
                for path, end in self._good_paths(
                        x2, y2, dx, dy, budget - self.t_price, seen):
                    yield (T,) + path, end
            seen.remove((x1, y1))
        seen.remove((x, y))

    def _lookup(self, dx, dy, xn, yn, dxn, dyn):
        xt, yt = unrotate(xn, yn, dx, dy)
        dxt, dyt = unrotate(dxn, dyn, dx, dy)
        return self.inv[xt, yt, dxt, dyt]


class Grid:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.grid = {}

    def __setitem__(self, key, val):
        self.grid[key] = val

    def __getitem__(self, key):
        return self.grid.get(key, ' ')

    def __contains__(self, key):
        return key in self.grid

    def __iter__(self):
        return iter(self.grid.items())

    def clear(self):
        self.grid.clear()

    def values(self):
        return self.grid.values()

    def shrink(self):
        small = Grid(self.w // 2, self.h // 2)
        for y in range(self.h // 2):
            for x in range(self.w // 2):
                small[x, y] = self[2 * x + 1, 2 * y + 1]
        return small

    def test_path(self, path, x0, y0, dx0=0, dy0=1):
        return all(
            0 <= x0 - x + y < self.w and 0 <= y0 + x + y < self.h
            and (x0 - x + y, y0 + x + y) not in self
            for x, y in path.xys(dx0, dy0))

    def draw_path(self, path, x0, y0, dx0=0, dy0=1, loop=False):
        ps = list(path.xys(dx0, dy0))
        if loop:
            assert ps[0] == ps[-1]
            ps.append(ps[1])
        for i in range(1, len(ps) - 1):
            xp, yp = ps[i - 1]
            x, y = ps[i]
            xn, yn = ps[i + 1]
            self[x0 - x + y, y0 + x + y] = {
                (1, 1, 1): '<', (-1, -1, -1): '<',
                (1, 1, -1): '>', (-1, -1, 1): '>',
                (-1, 1, 1): 'v', (1, -1, -1): 'v',
                (-1, 1, -1): '^', (1, -1, 1): '^',
                (0, 2, 0): '\\', (0, -2, 0): '\\',
                (2, 0, 0): '/', (-2, 0, 0): '/'
            }[xn - xp, yn - yp, sign((x - xp) * (yn - y) - (xn - x) * (y - yp))]

    def make_tubes(self):
        uf = UnionFind()
        tube_grid = Grid(self.w, self.h)
        for x in range(self.w):
            d = '-'
            for y in range(self.h):
                for dx, dy in {
                    '/-': [(0, 1)], '\\-': [(1, 0), (0, 1)],
                    '/|': [(1, 0)],
                    ' -': [(1, 0)], ' |': [(0, 1)],
                    'v|': [(0, 1)], '>|': [(1, 0)],
                    'v-': [(0, 1)], '>-': [(1, 0)],
                }.get(self[x, y] + d, []):
                    uf.union((x, y), (x + dx, y + dy))
                tube_grid[x, y] = {
                    '/-': '┐', '\\-': '┌',
                    '/|': '└', '\\|': '┘',
                    ' -': '-', ' |': '|',
                }.get(self[x, y] + d, 'x')
                if self[x, y] in '\\/v^':
                    d = '|' if d == '-' else '-'
        return tube_grid, uf

    def clear_path(self, path, x, y):
        path_grid = Grid(self.w, self.h)
        path_grid.draw_path(path, x, y, loop=True)
        for key, val in path_grid.make_tubes()[0]:
            if val == '|':
                self.grid.pop(key, None)


LOOP_TRIES = 1000


def has_loops(grid, uf):
    groups = len({uf.find((x, y)) for y in range(grid.h) for x in range(grid.w)})
    ends = sum(bool(grid[x, y] in 'v^<>') for y in range(grid.h) for x in range(grid.w))
    return ends != 2 * groups


def has_pair(tg, uf):
    for y in range(tg.h):
        for x in range(tg.w):
            for dx, dy in ((1, 0), (0, 1)):
                x1, y1 = x + dx, y + dy
                if x1 < tg.w and y1 < tg.h:
                    if tg[x, y] == tg[x1, y1] == 'x' \
                            and uf.find((x, y)) == uf.find((x1, y1)):
                        return True
    return False


def has_tripple(tg, uf):
    for y in range(tg.h):
        for x in range(tg.w):
            r = uf.find((x, y))
            nbs = 0
            for dx, dy in ((1, 0), (0, 1), (-1, 0), (0, -1)):
                x1, y1 = x + dx, y + dy
                if 0 <= x1 < tg.w and 0 <= y1 < tg.h and uf.find((x1, y1)) == r:
                    nbs += 1
            if nbs >= 3:
                return True
    return False


def make(w, h, mitm, min_numbers=0, max_numbers=1000):
    def test_ready(grid):
        sg = grid.shrink()
        stg, uf = sg.make_tubes()
        numbers = list(stg.values()).count('x') // 2
        return (min_numbers <= numbers <= max_numbers
                and not has_loops(sg, uf)
                and not has_pair(stg, uf)
                and not has_tripple(stg, uf))

    grid = Grid(2 * w + 1, 2 * h + 1)

    for _ in range(2000):
        grid.clear()

        path = mitm.rand_path2(h, h, 0, -1)
        if path is None or not grid.test_path(path, 0, 0):
            continue
        grid.draw_path(path, 0, 0)
        grid[0, 0], grid[0, 2 * h] = '\\', '/'

        path2 = mitm.rand_path2(h, h, 0, -1)
        if path2 is None or not grid.test_path(path2, 2 * w, 2 * h, 0, -1):
            continue
        grid.draw_path(path2, 2 * w, 2 * h, 0, -1)
        grid[2 * w, 0], grid[2 * w, 2 * h] = '/', '\\'

        if test_ready(grid):
            return grid.shrink()

        tg, _ = grid.make_tubes()
        for tries in range(LOOP_TRIES):
            x, y = 2 * random.randrange(w), 2 * random.randrange(h)
            if tg[x, y] not in '-|':
                continue
            loop = mitm.rand_loop(clock=1 if tg[x, y] == '-' else -1)
            if loop is None:
                continue
            if grid.test_path(loop, x, y):
                grid.clear_path(loop, x, y)
                grid.draw_path(loop, x, y, loop=True)
                tg, _ = grid.make_tubes()

                sg = grid.shrink()
                stg, uf = sg.make_tubes()
                numbers = list(stg.values()).count('x') // 2
                if numbers > max_numbers:
                    break
                if test_ready(grid):
                    return sg

    return None


def short_pair_violation(path_lengths):
    """Reject too-easy puzzles based on flow (path) length in cells. The number
    of empty squares between a pair's endpoints is (length - 2). Rules:
      - no adjacent endpoints        -> no flow of length <= 2 (0 between)
      - <= 1 pair with 1 between      -> at most one flow of length 3
      - <= 2 pairs with <= 2 between  -> at most two flows of length <= 4
    """
    if any(l <= 2 for l in path_lengths):
        return True
    if sum(1 for l in path_lengths if l == 3) > 1:
        return True
    if sum(1 for l in path_lengths if l <= 4) > 2:
        return True
    return False


def grid_to_level(grid, level_id, w, h):
    tube_grid, uf = grid.make_tubes()

    group_sizes = {}
    for y in range(grid.h):
        for x in range(grid.w):
            g = uf.find((x, y))
            group_sizes[g] = group_sizes.get(g, 0) + 1

    groups = defaultdict(list)
    for y in range(grid.h):
        for x in range(grid.w):
            if tube_grid[x, y] == 'x':
                groups[uf.find((x, y))].append([y, x])

    endpoints = []
    path_lengths = []
    color = 0
    for group_id, cells in groups.items():
        if len(cells) == 2:
            endpoints.append({"color": color, "cells": cells})
            path_lengths.append(group_sizes[group_id])
            color += 1

    if not endpoints:
        return None

    if short_pair_violation(path_lengths):
        return None

    return {
        "id": level_id,
        "rows": h,
        "cols": w,
        "endpoints": endpoints,
        "optimalMoves": len(endpoints)
    }


# ===================== Shape Masks (non-rectangular boards) =====================
# Cells are (row, col) tuples. Every shape is a subset of a rectangular grid
# with normal 4-neighbour adjacency; "holes" are grid cells not in the set.

_DIRS4 = ((-1, 0), (1, 0), (0, -1), (0, 1))


def compute_4_adjacency(cells):
    """{cell: [neighbours]} using 4-neighbour adjacency restricted to cells."""
    cellset = set(cells)
    return {
        (r, c): [(r + dr, c + dc) for dr, dc in _DIRS4 if (r + dr, c + dc) in cellset]
        for (r, c) in cellset
    }


def is_connected(cells):
    """True if the cell set is 4-connected (empty/singleton => True)."""
    cellset = set(cells)
    if len(cellset) <= 1:
        return True
    start = next(iter(cellset))
    seen = {start}
    stack = [start]
    while stack:
        r, c = stack.pop()
        for dr, dc in _DIRS4:
            n = (r + dr, c + dc)
            if n in cellset and n not in seen:
                seen.add(n)
                stack.append(n)
    return len(seen) == len(cellset)


def largest_component(cells):
    cellset = set(cells)
    seen = set()
    best = set()
    for cell in cellset:
        if cell in seen:
            continue
        comp = set()
        stack = [cell]
        seen.add(cell)
        while stack:
            r, c = stack.pop()
            comp.add((r, c))
            for dr, dc in _DIRS4:
                n = (r + dr, c + dc)
                if n in cellset and n not in seen:
                    seen.add(n)
                    stack.append(n)
        if len(comp) > len(best):
            best = comp
    return best


def normalize_cells(cells):
    """Shift so the bounding box starts at (0, 0)."""
    if not cells:
        return set()
    min_r = min(r for r, c in cells)
    min_c = min(c for r, c in cells)
    return {(r - min_r, c - min_c) for (r, c) in cells}


def fill_interior_holes(cells):
    """Fill empty cells fully enclosed by the shape (flood-fill from outside)."""
    cellset = set(cells)
    if not cellset:
        return cellset
    max_r = max(r for r, c in cellset)
    max_c = max(c for r, c in cellset)
    outside = set()
    stack = [(-1, -1)]
    while stack:
        r, c = stack.pop()
        if (r, c) in outside or (r, c) in cellset:
            continue
        if r < -1 or r > max_r + 1 or c < -1 or c > max_c + 1:
            continue
        outside.add((r, c))
        for dr, dc in _DIRS4:
            stack.append((r + dr, c + dc))
    result = set(cellset)
    for r in range(max_r + 1):
        for c in range(max_c + 1):
            if (r, c) not in cellset and (r, c) not in outside:
                result.add((r, c))
    return result


def rect_cells(w, h):
    """Full w×h rectangle."""
    return {(r, c) for r in range(h) for c in range(w)}


def hourglass_cells(w, h):
    """Two full-width rectangles joined by a 1-column-wide neck."""
    band = max(1, h // 3)
    mid_col = w // 2
    cells = set()
    for r in range(band):
        for c in range(w):
            cells.add((r, c))
    for r in range(h - band, h):
        for c in range(w):
            cells.add((r, c))
    for r in range(band, h - band):
        cells.add((r, mid_col))
    return normalize_cells(cells)


def _generate_blob(box, seed, disk_radius, target_fill, erosion_prob, roundness_bias):
    """Seed a disk, accrete boundary cells (weighted toward present-neighbour
    count for roundness), erode some boundary cells for lumpiness, fill holes,
    keep the largest 4-connected component. Deterministic via seed."""
    rng = random.Random(seed)
    center = box // 2
    cells = set()
    for r in range(box):
        for c in range(box):
            if (r - center) ** 2 + (c - center) ** 2 <= disk_radius ** 2:
                cells.add((r, c))
    if not cells:
        cells.add((center, center))
    target = int(box * box * target_fill)

    def present_neighbours(cell, s):
        r, c = cell
        return sum(1 for dr, dc in _DIRS4 if (r + dr, c + dc) in s)

    guard = 0
    while len(cells) < target and guard < box * box * 20:
        guard += 1
        frontier = set()
        for (r, c) in cells:
            for dr, dc in _DIRS4:
                n = (r + dr, c + dc)
                if 0 <= n[0] < box and 0 <= n[1] < box and n not in cells:
                    frontier.add(n)
        if not frontier:
            break
        frontier = sorted(frontier)
        weights = [present_neighbours(f, cells) ** roundness_bias for f in frontier]
        cells.add(rng.choices(frontier, weights=weights)[0])

    boundary = sorted(
        cell for cell in cells
        if any((cell[0] + dr, cell[1] + dc) not in cells for dr, dc in _DIRS4)
    )
    rng.shuffle(boundary)
    for cell in boundary:
        if rng.random() < erosion_prob:
            trial = cells - {cell}
            if trial and is_connected(trial):
                cells = trial

    cells = fill_interior_holes(cells)
    cells = largest_component(cells)
    return normalize_cells(cells)


def blob_cells(size=8, seed=0):
    """~size×size, roundish random connected shape."""
    return _generate_blob(
        box=size, seed=seed, disk_radius=size * 0.28,
        target_fill=0.68, erosion_prob=0.15, roundness_bias=3.0)


def inkblot_cells(size=13, seed=0):
    """~size×size, larger and lumpier than blob."""
    return _generate_blob(
        box=size, seed=seed, disk_radius=size * 0.2,
        target_fill=0.72, erosion_prob=0.35, roundness_bias=1.3)


def walls_cells(n, seed=0):
    """Full n×n with straight wall segments carved out; walls scale with n."""
    rng = random.Random(seed)
    cells = rect_cells(n, n)
    num_walls = max(2, (n - 5) // 2 + 2)
    max_len = min(6, n - 2)
    placed = 0
    guard = 0
    while placed < num_walls and guard < num_walls * 50:
        guard += 1
        length = rng.randint(3, max(3, max_len))
        if rng.random() < 0.5:
            r = rng.randint(0, n - 1)
            c0 = rng.randint(0, n - length)
            seg = {(r, c0 + i) for i in range(length)}
        else:
            c = rng.randint(0, n - 1)
            r0 = rng.randint(0, n - length)
            seg = {(r0 + i, c) for i in range(length)}
        trial = cells - seg
        if len(trial) >= 2 and is_connected(trial):
            cells = trial
            placed += 1
    return normalize_cells(cells)


# ===================== Graph-based Carve Generator (masked boards) =====================
# Python port of LevelGenerator.tryGenerate (Kotlin). Greedily builds flows over
# the cell set, only extending into cells whose removal keeps the remaining
# unmarked set connected, so the board is fully covered by construction.

def articulation_points(nodes, adjacency):
    """Cut vertices of the subgraph induced by `nodes` (iterative Tarjan). A cell
    is a cut vertex iff removing it disconnects the (connected) node set — so
    `cell not in articulation_points(nodes)` == "removing cell keeps connected"."""
    if len(nodes) <= 2:
        return set()
    disc = {}
    low = {}
    ap = set()
    timer = 0
    for root in nodes:
        if root in disc:
            continue
        root_children = 0
        parent = {root: None}
        disc[root] = low[root] = timer
        timer += 1
        stack = [(root, iter(adjacency[root]))]
        while stack:
            node, it = stack[-1]
            descended = False
            for nb in it:
                if nb not in nodes:
                    continue
                if nb not in disc:
                    parent[nb] = node
                    disc[nb] = low[nb] = timer
                    timer += 1
                    stack.append((nb, iter(adjacency[nb])))
                    if node == root:
                        root_children += 1
                    descended = True
                    break
                elif nb != parent[node]:
                    low[node] = min(low[node], disc[nb])
            if not descended:
                stack.pop()
                if stack:
                    par = stack[-1][0]
                    low[par] = min(low[par], low[node])
                    if parent[par] is not None and low[node] >= disc[par]:
                        ap.add(par)
        if root_children > 1:
            ap.add(root)
    return ap


def _connected_after_removal(unmarked, adjacency, candidate):
    remaining = unmarked - {candidate}
    if len(remaining) <= 1:
        return True
    start = next(iter(remaining))
    seen = {start}
    stack = [start]
    while stack:
        cur = stack.pop()
        for nb in adjacency[cur]:
            if nb in remaining and nb not in seen:
                seen.add(nb)
                stack.append(nb)
    return len(seen) == len(remaining)


def _pick_start(unmarked, adjacency, rng, cut_points=None):
    """Prefer a start cell whose removal keeps the unmarked set connected, so a
    flow never immediately strands a region."""
    if cut_points is None:
        cut_points = articulation_points(unmarked, adjacency)
    candidates = sorted(unmarked)
    rng.shuffle(candidates)
    for cand in candidates:
        if cand not in cut_points:
            return cand
    return candidates[0]


def carve_try(cells, adjacency, num_flows, rng):
    """One carve attempt. Returns list of paths (each a list of cells) or None."""
    unmarked = set(cells)
    paths = []
    for flow_index in range(num_flows):
        if not unmarked:
            break
        is_last = flow_index == num_flows - 1
        start = _pick_start(unmarked, adjacency, rng)
        path = [start]
        unmarked.discard(start)

        if is_last:
            max_len = len(unmarked) + 1
            min_len = max_len
        else:
            min_len = 3
            max_len = len(unmarked) - (num_flows - flow_index - 1) * 2

        while len(path) < max_len:
            current = path[-1]
            neighbors = [n for n in adjacency[current] if n in unmarked]
            rng.shuffle(neighbors)
            valid = [n for n in neighbors
                     if _connected_after_removal(unmarked, adjacency, n)]
            if not valid:
                break
            nxt = valid[0]
            path.append(nxt)
            unmarked.discard(nxt)
            if not is_last and len(path) >= min_len and rng.random() < 0.3:
                break

        if len(path) < 2:
            return None
        paths.append(path)

    if unmarked:
        return None
    return paths


def carve_cover(cells, adjacency, rng, early_stop_prob=0.25):
    """Path-cover with a dynamic number of flows: keep carving connectivity-
    preserving paths until every cell is used. Higher early_stop_prob yields
    more (shorter) flows. Retries a stranded start a few times before giving up.
    Returns list of paths or None if a cell cannot be covered.

    `remaining` is kept connected as an invariant, so "removing a cell keeps the
    set connected" reduces to "the cell is not an articulation point". We compute
    all articulation points once per step (O(V+E)) and take the first valid
    neighbour, instead of a BFS per candidate."""
    unmarked = set(cells)
    paths = []
    while unmarked:
        chosen = None
        for _retry in range(8):
            remaining = set(unmarked)
            cut = articulation_points(remaining, adjacency)
            start = _pick_start(remaining, adjacency, rng, cut)
            path = [start]
            remaining.discard(start)
            while remaining:
                current = path[-1]
                cut = articulation_points(remaining, adjacency)
                neighbors = [n for n in adjacency[current] if n in remaining]
                rng.shuffle(neighbors)
                nxt = next((n for n in neighbors if n not in cut), None)
                if nxt is None:
                    break
                path.append(nxt)
                remaining.discard(nxt)
                if len(path) >= 3 and rng.random() < early_stop_prob:
                    break
            if len(path) >= 2:
                chosen = path
                unmarked = remaining
                break
        if chosen is None:
            return None
        paths.append(chosen)
    return paths


# ===================== Solver Validation =====================

SOLVER_DIR = os.path.dirname(os.path.abspath(__file__))
SOLVER_SRC = os.path.join(SOLVER_DIR, "numberlink_solver.cpp")
SOLVER_BIN = os.path.join(SOLVER_DIR, "numberlink_solver")


def compile_solver():
    if os.path.exists(SOLVER_BIN) and os.path.getmtime(SOLVER_BIN) >= os.path.getmtime(SOLVER_SRC):
        return True
    print("Compiling NumberLink solver...")
    result = subprocess.run(
        ["g++", "-O2", "-o", SOLVER_BIN, SOLVER_SRC],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        print(f"Failed to compile solver: {result.stderr}", file=sys.stderr)
        return False
    return True


def validate_unique_solution(level, timeout=60):
    rows, cols = level["rows"], level["cols"]
    mask = {(r, c) for r, c in level["cells"]} if "cells" in level else None
    grid = [
        [0 if (mask is None or (r, c) in mask) else -1 for c in range(cols)]
        for r in range(rows)
    ]
    for ep in level["endpoints"]:
        color_num = ep["color"] + 1
        for r, c in ep["cells"]:
            grid[r][c] = color_num

    input_str = f"{cols} {rows}\n"
    for row in grid:
        input_str += " ".join(str(v) for v in row) + "\n"

    try:
        result = subprocess.run(
            [SOLVER_BIN],
            input=input_str, capture_output=True, text=True, timeout=timeout
        )
    except subprocess.TimeoutExpired:
        return False
    if result.returncode != 0:
        return False

    for line in result.stdout.strip().split("\n"):
        if "# of solutions:" in line:
            count_str = line.split(":")[-1].strip()
            try:
                count = int(float(count_str))
                return count == 1
            except ValueError:
                return False
    return False


# ===================== Pack Generation =====================

def carve_to_level(cells, paths, level_id):
    endpoints = [
        {"color": i, "cells": [list(path[0]), list(path[-1])]}
        for i, path in enumerate(paths)
    ]
    rows = max(r for r, c in cells) + 1
    cols = max(c for r, c in cells) + 1
    return {
        "id": level_id,
        "rows": rows,
        "cols": cols,
        "cells": sorted([list(cell) for cell in cells]),
        "endpoints": endpoints,
        "optimalMoves": len(endpoints),
    }


def generate_rect_pack(name, w, h, num_levels, flow_range, base_seed):
    # Cap the MITM loop budget: beyond ~12 the precomputed path tables make each
    # `make` call dramatically slower with no quality gain (9× slower at 15×15).
    budget = min(12, max(h, 6))
    mitm = Mitm(lr_price=2, t_price=1)
    mitm.prepare(budget)

    # On large boards the ZDD solver can take minutes on the rare hard instance;
    # cap validation low and just move on — there are plenty of candidates.
    val_timeout = 10 if h >= 11 else 60

    levels = []
    seed = base_seed
    max_attempts = num_levels * 500
    attempts = 0
    while len(levels) < num_levels and attempts < max_attempts:
        random.seed(seed)
        min_n, max_n = flow_range
        grid = make(w, h, mitm, min_n, max_n)
        if grid is not None:
            lid = f"{name.replace(' ', '_')}_{len(levels)+1:03d}"
            level = grid_to_level(grid, lid, w, h)
            if level is not None and validate_unique_solution(level, val_timeout):
                levels.append(level)
                sys.stdout.write(f"\r  {len(levels)}/{num_levels}")
                sys.stdout.flush()
        seed += 1
        attempts += 1
    print()
    return levels


def generate_tower_pack(name, w, heights, base_seed):
    """Pure (non-square) rectangles of width w with per-level heights. No cells
    key emitted — the loader defaults to a full rectangle from rows/cols."""
    num_levels = len(heights)
    levels = []
    seed = base_seed
    mitms = {}
    attempts = 0
    max_attempts = num_levels * 500
    while len(levels) < num_levels and attempts < max_attempts:
        attempts += 1
        h = heights[len(levels)]
        if h not in mitms:
            m = Mitm(lr_price=2, t_price=1)
            m.prepare(min(12, max(h, 6)))
            mitms[h] = m
        random.seed(seed)
        seed += 1
        grid = make(w, h, mitms[h], max(3, (w + h) // 4), w + h)
        if grid is None:
            continue
        lid = f"{name}_{len(levels)+1:03d}"
        level = grid_to_level(grid, lid, w, h)
        if level is not None and validate_unique_solution(level):
            levels.append(level)
            sys.stdout.write(f"\r  {len(levels)}/{num_levels}")
            sys.stdout.flush()
    print()
    return levels


def flow_ceiling(cells):
    """Max flows (endpoint pairs) allowed for a board of this size, so puzzles
    stay sparse with longer flows instead of a swarm of tiny 2-cell pairs.
    ~cells/5 keeps the average flow length around 5+."""
    return max(4, round(len(cells) / 5))


def generate_masked_pack(name, shape_fn, num_levels, base_seed,
                         early_stop=0.0, attempts_per_level=800):
    """Generate masked (non-rectangular) levels via the carve path-cover, keeping
    only uniquely-solvable ones with at most flow_ceiling(cells) flows.
    shape_fn(level_index, seed) -> set of cells."""
    levels = []
    seed = base_seed
    attempts = 0
    max_attempts = num_levels * attempts_per_level
    while len(levels) < num_levels and attempts < max_attempts:
        attempts += 1
        cells = shape_fn(len(levels), seed)
        seed += 1
        if len(cells) < 6 or not is_connected(cells):
            continue
        adjacency = compute_4_adjacency(cells)
        paths = carve_cover(cells, adjacency, random.Random(seed), early_stop)
        if not paths or len(paths) > flow_ceiling(cells):
            continue
        if short_pair_violation([len(p) for p in paths]):
            continue
        lid = f"{name}_{len(levels)+1:03d}"
        level = carve_to_level(cells, paths, lid)
        if validate_unique_solution(level, 10):
            levels.append(level)
            sys.stdout.write(f"\r  {len(levels)}/{num_levels} (attempts {attempts})")
            sys.stdout.flush()
    print()
    return levels


def square_flow_range(n):
    # Larger boards need more flows (endpoints) to stay uniquely solvable and to
    # keep the solver fast; small boards keep their original sparser ranges.
    if n <= 7:
        return (max(3, n // 2), n + 3)
    if n <= 10:
        return (n, 2 * n - 2)
    return (n + 2, 2 * n - 2)


# (filename_stem, display_name, shape_label, builder) — builder returns levels.
def build_registry():
    reg = []

    # Squares: 5×5 … 14×14 (5/6/7 are the existing packs, unchanged).
    square_seeds = {5: 10000, 6: 20000, 7: 30000}
    for n in range(8, 15):
        square_seeds[n] = n * 10000
    for n in range(5, 15):
        reg.append((
            f"{n}x{n}", f"{n}×{n}", "rectangular",
            (lambda n=n: {
                "name": f"{n}×{n}", "shape": "rectangular",
                "levels": generate_rect_pack(
                    f"{n}×{n}", n, n, 30, square_flow_range(n), square_seeds[n]),
            })
        ))

    # Tower: width 6, heights 8..12 (6 levels each => 30).
    heights = [8] * 6 + [9] * 6 + [10] * 6 + [11] * 6 + [12] * 6
    reg.append((
        "tower", "Tower", "tower",
        lambda: {
            "name": "Tower", "shape": "tower",
            "levels": generate_tower_pack("Tower", 6, heights, 40000),
        }
    ))

    # Hourglass: small bounding sizes (≤ ~59 cells) so a few long flows still
    # solve uniquely; larger boards force too many pairs / lose uniqueness.
    hg_feasible = [(8, 10), (9, 10), (8, 11), (9, 11)]
    hg_sizes = [hg_feasible[i % len(hg_feasible)] for i in range(30)]

    def hourglass_shape(idx, seed):
        w, h = hg_sizes[idx]
        return hourglass_cells(w, h)

    reg.append((
        "hourglass", "Hourglass", "hourglass",
        lambda: {
            "name": "Hourglass", "shape": "hourglass",
            "levels": generate_masked_pack(
                "Hourglass", hourglass_shape, 30, 50000),
        }
    ))

    # Blob: small round shapes (shape varies with seed).
    def blob_shape(idx, seed):
        return blob_cells(7 + (idx * 2) // 30, seed)   # 7..8

    reg.append((
        "blob", "Blob", "blob",
        lambda: {
            "name": "Blob", "shape": "blob",
            "levels": generate_masked_pack("Blob", blob_shape, 30, 60000),
        }
    ))

    # Inkblot: small lumpier random shapes. Kept at 8 — larger inkblots can't
    # satisfy the sparse short-pair rule while staying uniquely solvable.
    def inkblot_shape(idx, seed):
        return inkblot_cells(8, seed)

    reg.append((
        "inkblot", "Inkblot", "inkblot",
        lambda: {
            "name": "Inkblot", "shape": "inkblot",
            "levels": generate_masked_pack(
                "Inkblot", inkblot_shape, 30, 70000, attempts_per_level=2000),
        }
    ))

    # Walls: small base sizes 7..8 with carved wall segments.
    def walls_shape(idx, seed):
        n = 7 + (idx * 2) // 30    # 7..8
        return walls_cells(n, seed)

    reg.append((
        "walls", "Walls", "walls",
        lambda: {
            "name": "Walls", "shape": "walls",
            "levels": generate_masked_pack("Walls", walls_shape, 30, 80000),
        }
    ))

    return reg


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    selected = set(sys.argv[2:])  # optional filename stems to (re)generate

    if not compile_solver():
        print("Error: Could not compile NumberLink solver", file=sys.stderr)
        sys.exit(1)

    for stem, display, _shape, builder in build_registry():
        if selected and stem not in selected:
            continue
        print(f"Generating {display}...")
        pack_data = builder()
        path = f"{out_dir}/{stem}.json"
        with open(path, "w") as f:
            json.dump(pack_data, f, indent=2)
        print(f"  -> {len(pack_data['levels'])} levels written to {path}")


if __name__ == "__main__":
    main()
