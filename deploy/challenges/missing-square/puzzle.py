"""Public pieces/outlines; victory is checked geometrically without an answer table."""
PATTERNS = {
    'F': ['.XX', 'XX.', '.X.'], 'I': ['XXXXX'],
    'L': ['X.', 'X.', 'X.', 'XX'], 'P': ['XX', 'XX', 'X.'],
    'T': ['XXX', '.X.', '.X.'], 'V': ['X..', 'X..', 'XXX'],
    'Y': ['.X', 'XX', '.X', '.X'], 'Z': ['XX.', '.X.', '.XX'],
}
COLORS = ['#df937c', '#d7b75e', '#96b2a0', '#87a6ba',
          '#bba5c7', '#ccaf96', '#83babc', '#afb775']


def cells(pattern):
    return [[x, y] for y, row in enumerate(pattern) for x, c in enumerate(row) if c != '.']


PIECES = [{'id': name, 'cells': cells(pattern), 'color': color}
          for (name, pattern), color in zip(PATTERNS.items(), COLORS)]
BY_ID = {p['id']: p for p in PIECES}
BOARDS = {
    1: {'width': 8, 'height': 5, 'name': '矩形', 'cells': cells(['XXXXXXXX'] * 5)},
    2: {'width': 10, 'height': 6, 'name': '钥匙',
        'cells': cells(['XXXX......', 'XXXX......', 'XXXXXXXXXX',
                        'XXXXXXXXXX', 'XXXX..X.XX', 'XXXX..X...'])},
}
BOARD_SETS = {stage: set(map(tuple, board['cells'])) for stage, board in BOARDS.items()}


def oriented(piece_id, rotation, flipped):
    points = [(x * (-1 if flipped else 1), y) for x, y in BY_ID[piece_id]['cells']]
    for _ in range(rotation):
        points = [(-y, x) for x, y in points]
    x0, y0 = min(x for x, y in points), min(y for x, y in points)
    return [(x-x0, y-y0) for x, y in points]


def valid_move(move):
    if not isinstance(move, dict) or set(move) != {'id', 'zone', 'x', 'y', 'rotation', 'flipped'}:
        return False
    if not isinstance(move['id'], str) or move['id'] not in BY_ID:
        return False
    if move['zone'] not in ('source', 'target'):
        return False
    if any(type(move[k]) is not int for k in ('x', 'y', 'rotation')):
        return False
    if type(move['flipped']) is not bool or not 0 <= move['rotation'] < 4:
        return False
    if not (0 <= move['x'] <= 9 and 0 <= move['y'] <= 5):
        return False
    return move['zone'] != 'source' or (move['x'] == move['y'] == 0)


def occupied(move):
    if move['zone'] != 'target':
        return set()
    return {(x+move['x'], y+move['y'])
            for x, y in oriented(move['id'], move['rotation'], move['flipped'])}


def can_place(move, placements, stage):
    if not valid_move(move):
        return False
    area = occupied(move)
    if not area <= BOARD_SETS[stage]:
        return False
    return not any(area & occupied(other) for other in placements if other['id'] != move['id'])


def solved(placements, stage):
    if not isinstance(placements, list) or len(placements) != len(PIECES):
        return False
    seen, cover = set(), set()
    for move in placements:
        if not valid_move(move) or move['id'] in seen or move['zone'] != 'target':
            return False
        seen.add(move['id'])
        area = occupied(move)
        if cover & area or not area <= BOARD_SETS[stage]:
            return False
        cover |= area
    return seen == set(BY_ID) and cover == BOARD_SETS[stage]
