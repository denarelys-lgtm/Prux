package com.example.detectcamera;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AudioStreamManager {

    private static final String TAG = "AudioStreamManager";
    private static final int SAMPLE_RATE = 16000; // 16kHz es ideal para voz liviana
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;

    private final Set<PipedOutputStream> clientes = ConcurrentHashMap.newKeySet();

    @SuppressLint("MissingPermission")
    public synchronized void iniciarSiEsNecesario() {
        if (isRecording) return;

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize, 4096);

        try {
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC, // Fuente estándar no exclusiva
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
            );

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "No se pudo inicializar AudioRecord.");
                return;
            }

            audioRecord.startRecording();
            isRecording = true;

            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[2048];
                while (isRecording && !Thread.currentThread().isInterrupted()) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0) {
                        transmitirAClientes(buffer, read);
                    }
                }
            }, "AudioRecordThread");

            recordingThread.start();
            Log.i(TAG, "Captura de micrófono iniciada en segundo plano.");
        } catch (Exception e) {
            Log.e(TAG, "Error iniciando captura de micrófono: " + e.getMessage());
        }
    }

    private void transmitirAClientes(byte[] data, int length) {
        for (PipedOutputStream pos : clientes) {
            try {
                pos.write(data, 0, length);
                pos.flush();
            } catch (Exception e) {
                // Si el navegador se desconecta, cerramos su tubería
                cerrarCliente(pos);
            }
        }
    }

    public InputStream crearAudioStreamCliente() {
        try {
            PipedOutputStream pos = new PipedOutputStream();
            PipedInputStream pis = new PipedInputStream(pos, 65536) {
                @Override
                public void close() throws java.io.IOException {
                    super.close();
                    cerrarCliente(pos);
                }
            };

            // Escribir encabezado de Audio WAV para que HTML5 lo reproduzca directamente
            byte[] wavHeader = crearWavHeader(SAMPLE_RATE, 1, 16);
            pos.write(wavHeader);
            pos.flush();

            clientes.add(pos);
            iniciarSiEsNecesario();

            return pis;
        } catch (Exception e) {
            Log.e(TAG, "Error creando stream de audio: " + e.getMessage());
            return null;
        }
    }

    private synchronized void cerrarCliente(PipedOutputStream pos) {
        clientes.remove(pos);
        try {
            pos.close();
        } catch (Exception ignored) {}

        if (clientes.isEmpty()) {
            detenerCaptura();
        }
    }

    public synchronized void detenerCaptura() {
        isRecording = false;
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error deteniendo AudioRecord: " + e.getMessage());
            }
            audioRecord = null;
        }
        clientes.clear();
        Log.i(TAG, "Micrófono apagado (sin oyentes activos).");
    }

    private byte[] crearWavHeader(int sampleRate, int channels, int bitsPerSample) {
        long byteRate = (long) sampleRate * channels * bitsPerSample / 8;
        byte[] header = new byte[44];

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (0x70000000 & 0xff);
        header[5] = (byte) ((0x70000000 >> 8) & 0xff);
        header[6] = (byte) ((0x70000000 >> 16) & 0xff);
        header[7] = (byte) ((0x70000000 >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (channels * bitsPerSample / 8); header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (0x70000000 & 0xff);
        header[41] = (byte) ((0x70000000 >> 8) & 0xff);
        header[42] = (byte) ((0x70000000 >> 16) & 0xff);
        header[43] = (byte) ((0x70000000 >> 24) & 0xff);

        return header;
    }
}
