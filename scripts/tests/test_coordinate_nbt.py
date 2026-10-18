import gzip
import importlib.util
from pathlib import Path
import struct
import unittest
import zlib

spec = importlib.util.spec_from_file_location('coordinate_nbt', Path(__file__).parents[1] / 'model-coordinate-migration' / 'nbt.py')
nbt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nbt)


def string(raw):
    return struct.pack('>H', len(raw)) + raw


def fixture(value=b'old'):
    tag = b'\x08' + string(nbt.TARGET_KEY.encode()) + string(value)
    # A compound in a list, including an unrelated modified UTF-8 string.
    return b'\x0a\x00\x00\x09' + string(b'items') + b'\x0a\x00\x00\x00\x01' + b'\x08' + string(b'opaque') + string(b'\xc0\x80\xed\xa0\x80') + tag + b'\x00\x00'


def region(plain, compression=2):
    packed = zlib.compress(plain) if compression == 2 else gzip.compress(plain) if compression == 1 else plain
    chunk = struct.pack('>I', len(packed) + 1) + bytes([compression]) + packed
    sectors = (len(chunk) + 4095) // 4096
    header = bytearray(8192)
    header[:4] = ((2 << 8) | sectors).to_bytes(4, 'big')
    header[4096:4100] = b'ABCD'
    return bytes(header) + chunk + b'\0' * (sectors * 4096 - len(chunk))


class NbtTests(unittest.TestCase):
    def test_nested_and_opaque(self):
        original = fixture()
        changed, count = nbt.rewrite_nbt(original, lambda name, value: 'new')
        self.assertEqual((changed, count), (fixture(b'new'), 1))
        self.assertIs(nbt.rewrite_nbt(original, lambda *_: None)[0], original)

    def test_modified_utf8(self):
        value = 'x\0😀'
        encoded = nbt._encode_string(value)
        self.assertEqual(nbt._decode_string(encoded), value)
        changed, count = nbt.rewrite_nbt(fixture(), lambda *_: value)
        self.assertEqual(changed, fixture(encoded))
        self.assertEqual(count, 1)

    def test_dat_modes(self):
        for pack in (lambda x: x, gzip.compress, zlib.compress):
            original = pack(fixture())
            self.assertIs(nbt.rewrite_dat(original, lambda *_: None)[0], original)
            changed, count = nbt.rewrite_dat(original, lambda *_: 'new')
            self.assertEqual(count, 1)
            self.assertEqual(nbt.rewrite_dat(changed, lambda name, value: 'old')[1], 1)

    def test_region_modes_and_growth(self):
        for mode in (1, 2, 3):
            original = region(fixture(), mode)
            self.assertIs(nbt.rewrite_region(original, lambda *_: None)[0], original)
            changed, count = nbt.rewrite_region(original, lambda *_: 'new')
            self.assertEqual(count, 1)
            self.assertEqual(changed[4096:8192], original[4096:8192])
            self.assertEqual(nbt.rewrite_region(changed, lambda *_: None)[1], 0)
        original = region(fixture(), 3)
        changed, count = nbt.rewrite_region(original, lambda *_: 'x' * 9000)
        self.assertEqual(count, 1)
        self.assertEqual(changed[8192:12288], original[8192:12288])
        self.assertGreater(int.from_bytes(changed[:4], 'big') >> 8, 2)
        self.assertEqual(nbt.rewrite_region(changed, lambda *_: None)[1], 0)

    def test_invalid(self):
        for raw in (b'', fixture()[:-1], fixture() + b'x', b'\x0a\x00\x00\x07\x00\x00\xff\xff\xff\xff', b'\x0a\x00\x00\x09\x00\x00\x00\x00\x00\x00\x01'):
            with self.assertRaises(ValueError):
                nbt.rewrite_nbt(raw, lambda *_: None)
        with self.assertRaises(ValueError):
            nbt.rewrite_nbt(fixture(), lambda *_: 'x' * 65536)
        for raw in (b'', region(fixture())[:-1], region(fixture(), 4), region(fixture(), 130)):
            with self.assertRaises(ValueError):
                nbt.rewrite_region(raw, lambda *_: None)
        corrupt = bytearray(region(fixture()))
        corrupt[8192:8196] = (4096).to_bytes(4, 'big')
        with self.assertRaises(ValueError):
            nbt.rewrite_region(bytes(corrupt), lambda *_: None)
        corrupt = bytearray(region(fixture()))
        corrupt[:4] = ((3 << 8) | 1).to_bytes(4, 'big')
        with self.assertRaises(ValueError):
            nbt.rewrite_region(bytes(corrupt), lambda *_: None)
        with self.assertRaises(ValueError):
            nbt.rewrite_dat(gzip.compress(fixture())[:-2], lambda *_: None)
        overlap = bytearray(region(fixture()))
        overlap[4:8] = overlap[:4]
        with self.assertRaises(ValueError):
            nbt.rewrite_region(bytes(overlap), lambda *_: None)


if __name__ == '__main__':
    unittest.main()
