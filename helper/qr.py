"""A small QR code encoder (byte mode, error correction level M, versions 1-10), standard library only.

Enough for a web address, such as the admin panel's, shown on the TV for a phone camera to open. It follows the
QR Code specification (ISO/IEC 18004) the way Project Nayuki's reference generator does.
    matrix("http://192.0.2.1:8765/admin") -> list of rows, each a string of "1" (dark) and "0" (light)
"""

# Per version 1-10, level M: error-correction codewords per block, and number of blocks.
_ECC_PER_BLOCK = [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26]
_BLOCKS = [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5]
_FORMAT_M = 0   # the 2-bit format value of level M


def _bit(x: int, i: int) -> bool:
    return (x >> i) & 1 != 0


def _raw_modules(ver: int) -> int:
    n = (16 * ver + 128) * ver + 64
    if ver >= 2:
        align = ver // 7 + 2
        n -= (25 * align - 10) * align - 55
        if ver >= 7:
            n -= 36
    return n


def _data_codewords(ver: int) -> int:
    return _raw_modules(ver) // 8 - _ECC_PER_BLOCK[ver] * _BLOCKS[ver]


def _gf_mul(x: int, y: int) -> int:
    z = 0
    for i in reversed(range(8)):
        z = (z << 1) ^ ((z >> 7) * 0x11D)
        z ^= ((y >> i) & 1) * x
    return z


def _rs_divisor(degree: int) -> list:
    result = [0] * (degree - 1) + [1]
    root = 1
    for _ in range(degree):
        for j in range(len(result)):
            result[j] = _gf_mul(result[j], root)
            if j + 1 < len(result):
                result[j] ^= result[j + 1]
        root = _gf_mul(root, 0x02)
    return result


def _rs_remainder(data: list, divisor: list) -> list:
    result = [0] * len(divisor)
    for b in data:
        factor = b ^ result.pop(0)
        result.append(0)
        for i, coef in enumerate(divisor):
            result[i] ^= _gf_mul(coef, factor)
    return result


def _codewords(data: bytes) -> tuple[int, list]:
    for ver in range(1, 11):
        count_bits = 8 if ver < 10 else 16
        if 4 + count_bits + 8 * len(data) <= _data_codewords(ver) * 8:
            break
    else:
        raise ValueError("too long for this QR encoder")
    bits = [0, 1, 0, 0] + [(len(data) >> i) & 1 for i in reversed(range(count_bits))]
    for b in data:
        bits += [(b >> i) & 1 for i in reversed(range(8))]
    capacity = _data_codewords(ver) * 8
    bits += [0] * min(4, capacity - len(bits))
    bits += [0] * (-len(bits) % 8)
    words = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = 0xEC
    while len(words) < _data_codewords(ver):
        words.append(pad)
        pad ^= 0xEC ^ 0x11
    # Split into blocks, add error correction to each, and interleave.
    nblocks, ecc_len = _BLOCKS[ver], _ECC_PER_BLOCK[ver]
    raw = _raw_modules(ver) // 8
    short = nblocks - raw % nblocks
    short_len = raw // nblocks
    divisor = _rs_divisor(ecc_len)
    blocks, k = [], 0
    for i in range(nblocks):
        dat = words[k:k + short_len - ecc_len + (0 if i < short else 1)]
        k += len(dat)
        ecc = _rs_remainder(dat, divisor)
        if i < short:
            dat = dat + [0]
        blocks.append(dat + ecc)
    out = []
    for i in range(len(blocks[0])):
        for j, blk in enumerate(blocks):
            if i != short_len - ecc_len or j >= short:
                out.append(blk[i])
    return ver, out


def _mask(m: int, x: int, y: int) -> bool:
    return [(x + y) % 2 == 0, y % 2 == 0, x % 3 == 0, (x + y) % 3 == 0, (x // 3 + y // 2) % 2 == 0,
            x * y % 2 + x * y % 3 == 0, (x * y % 2 + x * y % 3) % 2 == 0, ((x + y) % 2 + x * y % 3) % 2 == 0][m]


def _penalty(mod: list) -> int:
    n, score = len(mod), 0
    for lines in (mod, [list(c) for c in zip(*mod)]):
        for line in lines:
            run, prev = 0, None
            for v in line:
                run = run + 1 if v == prev else 1
                prev = v
                if run == 5:
                    score += 3
                elif run > 5:
                    score += 1
    for y in range(n - 1):
        for x in range(n - 1):
            if mod[y][x] == mod[y][x + 1] == mod[y + 1][x] == mod[y + 1][x + 1]:
                score += 3
    dark = sum(sum(r) for r in mod)
    score += abs(dark * 20 - n * n * 10) // (n * n) * 10
    return score


def matrix(text: str) -> list:
    ver, words = _codewords(text.encode("utf-8"))
    size = ver * 4 + 17
    mod = [[False] * size for _ in range(size)]
    fn = [[False] * size for _ in range(size)]

    def put(x, y, dark):
        mod[y][x] = dark
        fn[y][x] = True

    for i in range(size):   # timing
        put(6, i, i % 2 == 0)
        put(i, 6, i % 2 == 0)
    for cx, cy in ((3, 3), (size - 4, 3), (3, size - 4)):   # finders with separators
        for dy in range(-4, 5):
            for dx in range(-4, 5):
                x, y = cx + dx, cy + dy
                if 0 <= x < size and 0 <= y < size:
                    put(x, y, max(abs(dx), abs(dy)) not in (2, 4))
    if ver > 1:   # alignment patterns
        n = ver // 7 + 2
        step = (ver * 8 + n * 3 + 5) // (n * 4 - 4) * 2
        pos = list(reversed([size - 7 - i * step for i in range(n - 1)] + [6]))
        for i, a in enumerate(pos):
            for j, b in enumerate(pos):
                if (i, j) in ((0, 0), (0, n - 1), (n - 1, 0)):
                    continue
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        put(a + dx, b + dy, max(abs(dx), abs(dy)) != 1)

    def format_bits(m):
        data = _FORMAT_M << 3 | m
        rem = data
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        bits = (data << 10 | rem) ^ 0x5412
        for i in range(6):
            put(8, i, _bit(bits, i))
        put(8, 7, _bit(bits, 6))
        put(8, 8, _bit(bits, 7))
        put(7, 8, _bit(bits, 8))
        for i in range(9, 15):
            put(14 - i, 8, _bit(bits, i))
        for i in range(8):
            put(size - 1 - i, 8, _bit(bits, i))
        for i in range(8, 15):
            put(8, size - 15 + i, _bit(bits, i))
        put(8, size - 8, True)

    format_bits(0)   # reserve the format areas
    if ver >= 7:
        rem = ver
        for _ in range(12):
            rem = (rem << 1) ^ ((rem >> 11) * 0x1F25)
        bits = ver << 12 | rem
        for i in range(18):
            a, b = size - 11 + i % 3, i // 3
            put(a, b, _bit(bits, i))
            put(b, a, _bit(bits, i))

    i = 0   # the codewords, in the zigzag order
    for right in range(size - 1, 0, -2):
        if right <= 6:
            right -= 1
        for vert in range(size):
            for j in range(2):
                x = right - j
                y = size - 1 - vert if (right + 1) & 2 == 0 else vert
                if not fn[y][x] and i < len(words) * 8:
                    mod[y][x] = _bit(words[i >> 3], 7 - (i & 7))
                    i += 1

    best, best_score = None, None
    for m in range(8):
        trial = [[mod[y][x] ^ (not fn[y][x] and _mask(m, x, y)) for x in range(size)] for y in range(size)]
        saved = [row[:] for row in mod]
        mod[:] = trial
        format_bits(m)
        score = _penalty(mod)
        if best_score is None or score < best_score:
            best, best_score = [row[:] for row in mod], score
        mod[:] = saved
    return ["".join("1" if v else "0" for v in row) for row in best]
