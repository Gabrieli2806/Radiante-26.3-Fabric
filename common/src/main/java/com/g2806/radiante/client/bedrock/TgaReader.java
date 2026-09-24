package com.g2806.radiante.client.bedrock;

import java.io.IOException;

/**
 * The Truevision TGA files Bedrock packs ship their textures in, which Java's image readers do not handle: true
 * colour or grey, 8/24/32 bits, raw or run-length encoded, either origin.
 */
final class TgaReader {

    private TgaReader() {
    }

    /** Decodes to non-premultiplied ARGB pixels, top row first. */
    static Image read(byte[] data) throws IOException {
        if (data.length < 18) {
            throw new IOException("TGA too short");
        }
        int idLength = data[0] & 0xFF;
        int colorMapType = data[1] & 0xFF;
        int imageType = data[2] & 0xFF;
        int colorMapLength = u16(data, 5);
        int colorMapDepth = data[7] & 0xFF;
        int width = u16(data, 12);
        int height = u16(data, 14);
        int bits = data[16] & 0xFF;
        int descriptor = data[17] & 0xFF;
        boolean topOrigin = (descriptor & 0x20) != 0;
        boolean rightOrigin = (descriptor & 0x10) != 0;
        if (colorMapType != 0 || (imageType != 2 && imageType != 3 && imageType != 10 && imageType != 11)) {
            throw new IOException("Unsupported TGA type " + imageType);
        }
        boolean grey = imageType == 3 || imageType == 11;
        boolean rle = imageType == 10 || imageType == 11;
        int bytesPerPixel = bits / 8;
        if (bytesPerPixel < 1 || bytesPerPixel > 4 || width <= 0 || height <= 0) {
            throw new IOException("Unsupported TGA format " + bits + " bits " + width + "x" + height);
        }

        int offset = 18 + idLength + colorMapLength * ((colorMapDepth + 7) / 8);
        int[] pixels = new int[width * height];
        int index = 0;
        int count = width * height;
        while (index < count) {
            if (rle) {
                int header = data[offset++] & 0xFF;
                int run = (header & 0x7F) + 1;
                if ((header & 0x80) != 0) {
                    int pixel = pixel(data, offset, bytesPerPixel, grey);
                    offset += bytesPerPixel;
                    for (int i = 0; i < run && index < count; i++) {
                        pixels[index++] = pixel;
                    }
                } else {
                    for (int i = 0; i < run && index < count; i++) {
                        pixels[index++] = pixel(data, offset, bytesPerPixel, grey);
                        offset += bytesPerPixel;
                    }
                }
            } else {
                pixels[index++] = pixel(data, offset, bytesPerPixel, grey);
                offset += bytesPerPixel;
            }
        }

        int[] ordered = new int[count];
        for (int y = 0; y < height; y++) {
            int sourceRow = topOrigin ? y : height - 1 - y;
            for (int x = 0; x < width; x++) {
                int sourceX = rightOrigin ? width - 1 - x : x;
                ordered[y * width + x] = pixels[sourceRow * width + sourceX];
            }
        }
        return new Image(width, height, ordered);
    }

    private static int pixel(byte[] data, int offset, int bytesPerPixel, boolean grey) {
        if (grey || bytesPerPixel == 1) {
            int v = data[offset] & 0xFF;
            int a = bytesPerPixel >= 2 ? data[offset + 1] & 0xFF : 255;
            return a << 24 | v << 16 | v << 8 | v;
        }
        int b = data[offset] & 0xFF;
        int g = data[offset + 1] & 0xFF;
        int r = data[offset + 2] & 0xFF;
        int a = bytesPerPixel == 4 ? data[offset + 3] & 0xFF : 255;
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    /** ARGB pixels, top row first. */
    record Image(int width, int height, int[] argb) {

        int get(int x, int y) {
            return this.argb[Math.floorMod(y, this.height) * this.width + Math.floorMod(x, this.width)];
        }

        /** Nearest-neighbour resample, for maps authored at another resolution than their colour texture. */
        Image resized(int newWidth, int newHeight) {
            if (newWidth == this.width && newHeight == this.height) {
                return this;
            }
            int[] out = new int[newWidth * newHeight];
            for (int y = 0; y < newHeight; y++) {
                for (int x = 0; x < newWidth; x++) {
                    out[y * newWidth + x] = this.get(x * this.width / newWidth, y * this.height / newHeight);
                }
            }
            return new Image(newWidth, newHeight, out);
        }
    }
}
