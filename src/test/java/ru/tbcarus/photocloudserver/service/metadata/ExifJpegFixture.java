package ru.tbcarus.photocloudserver.service.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Минимальный JPEG для тестов: SOI, APP1 с TIFF-структурой (IFD0 → Exif SubIFD с ASCII-тегами) и EOI.
 * Пикселей нет — metadata-extractor и Tika определяют JPEG и читают EXIF без них.
 */
public final class ExifJpegFixture {

    public static final int DATE_TIME_ORIGINAL = 0x9003;
    public static final int DATE_TIME_DIGITIZED = 0x9004;
    public static final int OFFSET_TIME_ORIGINAL = 0x9011;
    public static final int SUBSEC_TIME_ORIGINAL = 0x9291;

    private static final int EXIF_IFD_POINTER = 0x8769;
    private static final short TYPE_ASCII = 2;
    private static final short TYPE_LONG = 4;
    private static final int IFD_ENTRY_SIZE = 12;

    private ExifJpegFixture() {
    }

    public static byte[] jpegWithExifTags(Map<Integer, String> asciiTags) {
        // Записи IFD по стандарту TIFF упорядочены по номеру тега.
        TreeMap<Integer, String> tags = new TreeMap<>(asciiTags);
        int subIfdOffset = 8 + 2 + IFD_ENTRY_SIZE + 4;
        int dataOffset = subIfdOffset + 2 + IFD_ENTRY_SIZE * tags.size() + 4;

        ByteBuffer tiff = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);

        tiff.putShort((short) 1);
        tiff.putShort((short) EXIF_IFD_POINTER).putShort(TYPE_LONG).putInt(1).putInt(subIfdOffset);
        tiff.putInt(0);

        tiff.putShort((short) tags.size());
        List<byte[]> outOfLineValues = new ArrayList<>();
        int nextValueOffset = dataOffset;
        for (Map.Entry<Integer, String> tag : tags.entrySet()) {
            byte[] value = (tag.getValue() + "\0").getBytes(StandardCharsets.US_ASCII);
            tiff.putShort(tag.getKey().shortValue()).putShort(TYPE_ASCII).putInt(value.length);
            if (value.length <= 4) {
                // Значение до 4 байт хранится прямо в записи IFD.
                tiff.put(Arrays.copyOf(value, 4));
            } else {
                tiff.putInt(nextValueOffset);
                nextValueOffset += value.length;
                outOfLineValues.add(value);
            }
        }
        tiff.putInt(0);
        outOfLineValues.forEach(tiff::put);
        byte[] tiffBytes = Arrays.copyOf(tiff.array(), tiff.position());

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        jpeg.write(0xFF);
        jpeg.write(0xD8);
        jpeg.write(0xFF);
        jpeg.write(0xE1);
        int app1Length = 2 + 6 + tiffBytes.length;
        jpeg.write(app1Length >> 8);
        jpeg.write(app1Length & 0xFF);
        jpeg.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        jpeg.writeBytes(tiffBytes);
        jpeg.write(0xFF);
        jpeg.write(0xD9);
        return jpeg.toByteArray();
    }
}
