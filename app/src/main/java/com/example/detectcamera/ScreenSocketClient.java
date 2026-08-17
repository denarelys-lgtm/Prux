package com.example.detectcamera;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Cliente local del ScreenDaemon.
 *
 * Objetivo: mínima latencia. Lee un frame, lo convierte una sola vez a JPEG
 * de salida y lo entrega al WebServer. No mantiene una cola de frames.
 */
public class ScreenSocketClient {

    private static final String TAG = "ScreenSocketClient";
    private static final String HOST = "127.0.0.1";
    private static final int PUERTO = 9090;

    private static final int CONNECT_TIMEOUT_MS = 1000;
    private static final int MAX_FRAME_SIZE = 30_000_000;

    // El panel actual está alrededor de 440 px; 720 conserva margen para
    // pantallas grandes sin disparar el ancho de banda.
    private static final int MAX_OUTPUT_WIDTH = 720;
    private static final int JPEG_QUALITY = 50;

    private final WebServer webServer;

    private volatile boolean running;
    private Thread workerThread;

    public ScreenSocketClient(WebServer webServer) {
        this.webServer = webServer;
    }

    public synchronized void start() {
        if (running) return;

        running = true;
        workerThread = new Thread(() -> {
            while (running) {
                try (Socket socket = new Socket()) {
                    socket.setTcpNoDelay(true);
                    socket.setKeepAlive(true);
                    socket.setReceiveBufferSize(1024 * 1024);
                    socket.connect(new InetSocketAddress(HOST, PUERTO), CONNECT_TIMEOUT_MS);

                    try (DataInputStream dis = new DataInputStream(socket.getInputStream())) {
                        Log.i(TAG, "ScreenDaemon conectado en 127.0.0.1:9090");

                        while (running && !socket.isClosed()) {
                            int length = dis.readInt();

                            if (length <= 0 || length > MAX_FRAME_SIZE) {
                                Log.w(TAG, "Tamaño de frame inválido: " + length);
                                break;
                            }

                            byte[] frame = new byte[length];
                            dis.readFully(frame);

                            byte[] jpeg = procesarFrame(frame);
                            if (jpeg != null && jpeg.length > 0 && webServer != null) {
                                webServer.actualizarFramePantalla(jpeg);
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }, "ScreenSocketClient");

        workerThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (webServer != null) {
            webServer.actualizarFramePantalla(null);
        }
    }

    private boolean esJpeg(byte[] frame) {
        return frame.length >= 2
                && (frame[0] & 0xff) == 0xff
                && (frame[1] & 0xff) == 0xd8;
    }

    private byte[] procesarFrame(byte[] frame) {
        if (frame == null || frame.length == 0) return null;

        // Si el daemon ya entrega JPEG con el ancho correcto, no lo
        // decodificamos/recomprimimos: este camino ahorra muchísimo CPU.
        if (esJpeg(frame)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(frame, 0, frame.length, bounds);

            if (bounds.outWidth > 0 && bounds.outWidth <= MAX_OUTPUT_WIDTH) {
                return frame;
            }

            // Si es más grande, lo decodificamos con muestreo para ahorrar memoria
            return decodificarJpegConMuestreo(frame, bounds.outWidth, bounds.outHeight);
        }

        // JPEG/PNG/etc. comprimido.
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap bitmap = BitmapFactory.decodeByteArray(frame, 0, frame.length, options);

        if (bitmap != null) {
            try {
                return convertirBitmapAJpeg(bitmap);
            } finally {
                if (!bitmap.isRecycled()) bitmap.recycle();
            }
        }

        // RAW de screencap.
        return procesarRawFrameAJpeg(frame);
    }

    private byte[] decodificarJpegConMuestreo(byte[] jpegData, int originalWidth, int originalHeight) {
        if (originalWidth <= 0 || originalHeight <= 0) return null;

        // Calcula un inSampleSize potencia de 2 para reducir el tamaño de decodificación
        int inSampleSize = 1;
        int targetWidth = Math.min(MAX_OUTPUT_WIDTH, originalWidth);
        while (originalWidth / (inSampleSize * 2) >= targetWidth) {
            inSampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = inSampleSize;
        options.inPreferredConfig = Bitmap.Config.RGB_565;

        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length, options);
        if (bitmap == null) return null;

        try {
            // Si aún es más ancho que el máximo, se escala al tamaño exacto
            if (bitmap.getWidth() > MAX_OUTPUT_WIDTH) {
                return convertirBitmapAJpeg(bitmap);
            } else {
                // Ya tiene un tamaño adecuado, solo comprimir
                return convertirBitmapAJpeg(bitmap);
            }
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
        }
    }

    private byte[] procesarRawFrameAJpeg(byte[] rawFrame) {
        if (rawFrame.length < 12) return null;

        Bitmap bitmap = null;
        try {
            ByteBuffer buffer = ByteBuffer.wrap(rawFrame).order(ByteOrder.LITTLE_ENDIAN);

            int width = buffer.getInt();
            int height = buffer.getInt();
            int format = buffer.getInt();

            if (width <= 0 || height <= 0 || width > 4000 || height > 4000) {
                return null;
            }

            long pixelBytes = (long) width * height * 4L;
            if (pixelBytes <= 0 || pixelBytes > Integer.MAX_VALUE || pixelBytes > rawFrame.length) {
                return null;
            }

            int pixelDataSize = (int) pixelBytes;
            int offset = rawFrame.length - pixelDataSize;
            if (offset < 12 || offset >= rawFrame.length) {
                return null;
            }

            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            buffer.position(offset);
            buffer.limit(offset + pixelDataSize);
            bitmap.copyPixelsFromBuffer(buffer);

            return convertirBitmapAJpeg(bitmap);
        } catch (Throwable e) {
            Log.w(TAG, "Frame RAW descartado", e);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
    }

    private byte[] convertirBitmapAJpeg(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return null;
        }

        Bitmap scaled = null;
        try {
            int originalWidth = bitmap.getWidth();
            int originalHeight = bitmap.getHeight();

            int targetWidth = Math.min(MAX_OUTPUT_WIDTH, originalWidth);
            int targetHeight = Math.max(
                    1,
                    Math.round(originalHeight * (targetWidth / (float) originalWidth))
            );

            if (targetWidth != originalWidth) {
                scaled = Bitmap.createScaledBitmap(
                        bitmap, targetWidth, targetHeight, false
                );
            } else {
                scaled = bitmap;
            }

            ByteArrayOutputStream baos =
                    new ByteArrayOutputStream(Math.max(16 * 1024, targetWidth * targetHeight / 10));

            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)) {
                return null;
            }

            return baos.toByteArray();
        } catch (Throwable e) {
            Log.w(TAG, "Error codificando frame", e);
            return null;
        } finally {
            if (scaled != null && scaled != bitmap && !scaled.isRecycled()) {
                scaled.recycle();
            }
        }
    }
}
