"""Lossless targeted NBT string rewriting for offline world copies."""
import gzip
import struct
import zlib

TARGET_KEY = 'simplesignals:signal_wand_state'
MAX_CONTAINER = 16_777_216
MAX_UNCOMPRESSED = 256 * 1024 * 1024


def _decode_string(raw):
    # Java DataInput uses modified UTF-8, including encoded NUL and UTF-16
    # surrogate pairs. JSON often escapes these, but does not have to.
    units = raw.replace(b'\xc0\x80', b'\x00').decode('utf-8', 'surrogatepass')
    return units.encode('utf-16-be', 'surrogatepass').decode('utf-16-be', 'surrogatepass')


def _encode_string(value):
    utf16 = value.encode('utf-16-be', 'surrogatepass')
    units = ''.join(chr(int.from_bytes(utf16[i:i + 2], 'big')) for i in range(0, len(utf16), 2))
    return units.encode('utf-8', 'surrogatepass').replace(b'\x00', b'\xc0\x80')


def rewrite_nbt(data, callback, target_key=TARGET_KEY):
    pos = 0
    changes = []

    def take(n):
        nonlocal pos
        if n < 0 or pos + n > len(data):
            raise ValueError('Truncated NBT payload')
        start = pos
        pos += n
        return data[start:pos]

    def string():
        n = struct.unpack('>H', take(2))[0]
        start = pos
        raw = take(n)
        return start, raw

    def length():
        n = struct.unpack('>i', take(4))[0]
        if n < 0 or n > MAX_CONTAINER:
            raise ValueError('Invalid or oversized NBT container length')
        return n

    def payload(kind, name=b'', depth=0):
        if depth > 256:
            raise ValueError('NBT nesting exceeds 256')
        sizes = {1: 1, 2: 2, 3: 4, 4: 8, 5: 4, 6: 8}
        if kind in sizes:
            take(sizes[kind])
        elif kind in (7, 11, 12):
            take(length() * {7: 1, 11: 4, 12: 8}[kind])
        elif kind == 8:
            start, raw = string()
            if name == target_key.encode('ascii'):
                # Unrelated Java modified UTF-8 strings stay opaque and byte-identical.
                value = _decode_string(raw)
                replacement = callback(target_key, value)
                if replacement is not None and replacement != value:
                    encoded = _encode_string(replacement)
                    if len(encoded) > 65535:
                        raise ValueError('Replacement NBT string exceeds 65535 bytes')
                    changes.append((start - 2, start + len(raw), struct.pack('>H', len(encoded)) + encoded))
        elif kind == 9:
            item_kind = take(1)[0]
            n = length()
            if item_kind > 12 or (item_kind == 0 and n):
                raise ValueError('Invalid NBT list type')
            for _ in range(n):
                payload(item_kind, depth=depth + 1)
        elif kind == 10:
            while True:
                child = take(1)[0]
                if child == 0:
                    break
                if child > 12:
                    raise ValueError('Invalid NBT tag type')
                _, child_name = string()
                payload(child, child_name, depth + 1)
        else:
            raise ValueError('Invalid NBT tag type')

    root = take(1)[0]
    if root == 0:
        raise ValueError('NBT root cannot be TAG_End')
    _, name = string()
    payload(root, name)
    if pos != len(data):
        raise ValueError('Trailing bytes after NBT root')
    if not changes:
        return data, 0
    parts = []
    previous = 0
    for start, end, replacement in changes:
        parts.extend((data[previous:start], replacement))
        previous = end
    parts.append(data[previous:])
    return b''.join(parts), len(changes)


def _inflate(data, mode):
    try:
        obj = zlib.decompressobj(mode)
        result = obj.decompress(data, MAX_UNCOMPRESSED + 1)
        if len(result) > MAX_UNCOMPRESSED or obj.unconsumed_tail:
            raise ValueError('Compressed NBT exceeds 256 MiB limit')
        result += obj.flush()
        if not obj.eof or obj.unused_data or obj.unconsumed_tail:
            raise ValueError('Incomplete or trailing compressed NBT data')
        return result
    except zlib.error as exc:
        raise ValueError('Invalid compressed NBT data') from exc


def rewrite_dat(data, callback, target_key=TARGET_KEY):
    if data.startswith(b'\x1f\x8b'):
        mode = 'gzip'
        plain = _inflate(data, 31)
    elif len(data) >= 2 and data[0] & 15 == 8 and int.from_bytes(data[:2], 'big') % 31 == 0:
        mode = 'zlib'
        plain = _inflate(data, 15)
    else:
        mode = 'raw'
        plain = data
    rewritten, count = rewrite_nbt(plain, callback, target_key)
    if not count:
        return data, 0
    return (gzip.compress(rewritten, mtime=0) if mode == 'gzip' else
            zlib.compress(rewritten) if mode == 'zlib' else rewritten), count


def rewrite_region(data, callback, target_key=TARGET_KEY):
    if len(data) < 8192 or len(data) % 4096:
        raise ValueError('Invalid region header or sector alignment')
    occupied = {0, 1}
    chunks = []
    for i in range(1024):
        entry = int.from_bytes(data[i * 4:i * 4 + 4], 'big')
        offset, sectors = entry >> 8, entry & 255
        if not entry:
            continue
        if offset < 2 or not sectors or (offset + sectors) * 4096 > len(data):
            raise ValueError('Invalid region sector allocation')
        allocated = set(range(offset, offset + sectors))
        if occupied & allocated:
            raise ValueError('Overlapping region sectors')
        occupied.update(allocated)
        start = offset * 4096
        size = int.from_bytes(data[start:start + 4], 'big')
        if size < 1 or size + 4 > sectors * 4096:
            raise ValueError('Invalid region chunk length')
        compression = data[start + 4]
        if compression & 128:
            raise ValueError('External region chunks are unsupported')
        if compression not in (1, 2, 3):
            raise ValueError('Unsupported region compression type: %s' % compression)
        chunks.append((i, offset, sectors, compression, data[start + 5:start + 4 + size]))
    output = None
    total = 0
    for i, offset, sectors, compression, packed in chunks:
        plain = _inflate(packed, 31 if compression == 1 else 15) if compression != 3 else packed
        rewritten, count = rewrite_nbt(plain, callback, target_key)
        if not count:
            continue
        encoded = gzip.compress(rewritten, mtime=0) if compression == 1 else zlib.compress(rewritten) if compression == 2 else rewritten
        chunk = struct.pack('>I', len(encoded) + 1) + bytes([compression]) + encoded
        needed = (len(chunk) + 4095) // 4096
        if needed > 255:
            raise ValueError('Rewritten chunk exceeds 255 sectors')
        if output is None:
            output = bytearray(data)
        if needed > sectors:
            offset = len(output) // 4096
            if offset > 0xffffff:
                raise ValueError('Region offset exceeds 24 bits')
            sectors = needed
            output.extend(b'\0' * (sectors * 4096))
            output[i * 4:i * 4 + 4] = ((offset << 8) | sectors).to_bytes(4, 'big')
        start = offset * 4096
        output[start:start + len(chunk)] = chunk
        total += count
    return (bytes(output), total) if output is not None else (data, 0)
