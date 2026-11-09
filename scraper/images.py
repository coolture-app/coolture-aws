import struct
from dataclasses import dataclass


def image_size(data: bytes) -> tuple[int, int] | None:
    """Width and height from the header of a WebP, PNG or JPEG, or None for anything else."""
    if data[:4] == b'RIFF' and data[8:12] == b'WEBP':
        kind = data[12:16]
        if kind == b'VP8 ' and len(data) >= 30:
            width, height = struct.unpack('<HH', data[26:30])
            return width & 0x3FFF, height & 0x3FFF
        if kind == b'VP8L' and len(data) >= 25:
            b = data[21:25]
            return 1 + (((b[1] & 0x3F) << 8) | b[0]), 1 + (((b[3] & 0xF) << 10) | (b[2] << 2) | ((b[1] & 0xC0) >> 6))
        if kind == b'VP8X' and len(data) >= 30:
            return 1 + int.from_bytes(data[24:27], 'little'), 1 + int.from_bytes(data[27:30], 'little')
        return None
    if data[:8] == b'\x89PNG\r\n\x1a\n' and len(data) >= 24:
        return struct.unpack('>II', data[16:24])
    if data[:2] == b'\xff\xd8':
        i = 2
        while i + 9 < len(data):
            if data[i] != 0xFF:
                i += 1
                continue
            marker, length = data[i + 1], struct.unpack('>H', data[i + 2:i + 4])[0]
            # The start-of-frame markers, other than the ones for colour tables and arithmetic coding.
            if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
                height, width = struct.unpack('>HH', data[i + 5:i + 9])
                return width, height
            i += 2 + length
    return None


@dataclass(frozen=True)
class Image:
    content: bytes
    mime_type: str
    file_name: str
    width: int | None = None
    height: int | None = None

    @classmethod
    def read(cls, content: bytes, mime_type: str, file_name: str) -> 'Image':
        # A size of 0 would be refused by /media/uploads/init, so it counts as unknown.
        size = image_size(content)
        return cls(content, mime_type, file_name, *size) if size and all(size) else cls(content, mime_type, file_name)
