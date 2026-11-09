import struct

import pytest

from images import Image, image_size


def webp_lossy(width, height):
    return b'RIFF\0\0\0\0WEBPVP8 \0\0\0\0' + b'\0\0\0\x9d\x01\x2a' + struct.pack('<HH', width, height)


def webp_lossless(width, height):
    bits = (width - 1) | (height - 1) << 14
    return b'RIFF\0\0\0\0WEBPVP8L\0\0\0\0\x2f' + bits.to_bytes(4, 'little')


def webp_extended(width, height):
    return b'RIFF\0\0\0\0WEBPVP8X\x0a\0\0\0\0\0\0\0' + (width - 1).to_bytes(3, 'little') + (height - 1).to_bytes(3, 'little')


def png(width, height):
    return b'\x89PNG\r\n\x1a\n\0\0\0\rIHDR' + struct.pack('>II', width, height)


def jpeg(width, height, frame_marker=0xC0):
    app0 = b'\xff\xe0' + struct.pack('>H', 16) + b'JFIF\0' + b'\0' * 9
    huffman = b'\xff\xc4' + struct.pack('>H', 4) + b'\0\0'
    frame = bytes([0xFF, frame_marker]) + struct.pack('>HBHH', 17, 8, height, width)
    return b'\xff\xd8' + app0 + huffman + frame + b'\0' * 12


@pytest.mark.parametrize('header', [webp_lossy, webp_lossless, webp_extended, png, jpeg])
def test_size_is_read_from_the_header(header):
    assert image_size(header(1920, 1080)) == (1920, 1080)


def test_a_progressive_jpeg_is_read_too():
    assert image_size(jpeg(800, 1200, frame_marker=0xC2)) == (800, 1200)


@pytest.mark.parametrize('data', [b'', b'GIF89a', b'RIFF\0\0\0\0WEBPVP8 ', b'\xff\xd8\xff\xe0', png(1, 1)[:20]])
def test_an_unknown_or_cut_header_gives_no_size(data):
    assert image_size(data) is None


def test_an_image_whose_size_is_unknown_or_zero_has_none():
    assert Image.read(b'GIF89a', 'image/gif', 'a.gif') == Image(b'GIF89a', 'image/gif', 'a.gif')
    assert Image.read(png(0, 10), 'image/png', 'a.png').width is None


def test_an_image_keeps_its_size():
    assert Image.read(png(640, 480), 'image/png', 'a.png') == Image(png(640, 480), 'image/png', 'a.png', 640, 480)
