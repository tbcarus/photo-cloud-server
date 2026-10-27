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
 * Минимальный JPEG для тестов: SOI, APP1 с TIFF-структурой (IFD0 → Exif SubIFD с ASCII-тегами или GPS IFD) и EOI.
 * Пикселей нет — metadata-extractor и Tika определяют JPEG и читают EXIF без них.
 */
public final class ExifJpegFixture {

    public static final int DATE_TIME_ORIGINAL = 0x9003;
    public static final int DATE_TIME_DIGITIZED = 0x9004;
    public static final int OFFSET_TIME_ORIGINAL = 0x9011;
    public static final int SUBSEC_TIME_ORIGINAL = 0x9291;

    private static final int EXIF_IFD_POINTER = 0x8769;
    private static final int GPS_IFD_POINTER = 0x8825;
    private static final int GPS_LATITUDE_REF = 0x0001;
    private static final int GPS_LATITUDE = 0x0002;
    private static final int GPS_LONGITUDE_REF = 0x0003;
    private static final int GPS_LONGITUDE = 0x0004;
    private static final short TYPE_ASCII = 2;
    private static final short TYPE_LONG = 4;
    private static final short TYPE_RATIONAL = 5;
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
        return jpegWithTiff(Arrays.copyOf(tiff.array(), tiff.position()));
    }

    /**
     * JPEG с GPS IFD: LatitudeRef/Latitude/LongitudeRef/Longitude, координаты — градусы, минуты и секунды
     * с точностью до тысячной секунды, как пишут камеры. Северная широта и восточная долгота.
     */
    public static byte[] jpegWithGps(double latitude, double longitude) {
        int gpsIfdOffset = 8 + 2 + IFD_ENTRY_SIZE + 4;
        int dataOffset = gpsIfdOffset + 2 + IFD_ENTRY_SIZE * 4 + 4;

        ByteBuffer tiff = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);

        tiff.putShort((short) 1);
        tiff.putShort((short) GPS_IFD_POINTER).putShort(TYPE_LONG).putInt(1).putInt(gpsIfdOffset);
        tiff.putInt(0);

        tiff.putShort((short) 4);
        tiff.putShort((short) GPS_LATITUDE_REF).putShort(TYPE_ASCII).putInt(2).put(new byte[]{'N', 0, 0, 0});
        tiff.putShort((short) GPS_LATITUDE).putShort(TYPE_RATIONAL).putInt(3).putInt(dataOffset);
        tiff.putShort((short) GPS_LONGITUDE_REF).putShort(TYPE_ASCII).putInt(2).put(new byte[]{'E', 0, 0, 0});
        tiff.putShort((short) GPS_LONGITUDE).putShort(TYPE_RATIONAL).putInt(3).putInt(dataOffset + 24);
        tiff.putInt(0);
        putDegreesMinutesSeconds(tiff, latitude);
        putDegreesMinutesSeconds(tiff, longitude);
        return jpegWithTiff(Arrays.copyOf(tiff.array(), tiff.position()));
    }

    private static void putDegreesMinutesSeconds(ByteBuffer tiff, double value) {
        int degrees = (int) value;
        double minutesFraction = (value - degrees) * 60;
        int minutes = (int) minutesFraction;
        long secondsMillis = Math.round((minutesFraction - minutes) * 60 * 1000);
        tiff.putInt(degrees).putInt(1);
        tiff.putInt(minutes).putInt(1);
        tiff.putInt((int) secondsMillis).putInt(1000);
    }

    private static byte[] jpegWithTiff(byte[] tiffBytes) {
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
