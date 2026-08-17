package com.example.detectcamera;

import android.util.Base64;
import fi.iki.elonen.NanoHTTPD;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class WebServer extends NanoHTTPD {

    // Último frame disponible + secuencia. El servidor siempre entrega el frame
    // más reciente y nunca hace buffering de frames viejos.
    private byte[] ultimoFramePantalla = null;
    private byte[] ultimoFrameCamara = null;
    private long secuenciaPantalla = 0;
    private long secuenciaCamara = 0;
    private final Object frameLock = new Object();
    private String usuarioValido = "";
    private String passwordValida = "";
    private CameraService cameraService;
    private final AudioStreamManager audioStreamManager = new AudioStreamManager();

    public WebServer(int port) {
        super(port);
    }

    public void setCameraService(CameraService service) {
        this.cameraService = service;
    }

    public void setCredenciales(String user, String pass) {
        this.usuarioValido = user != null ? user.trim() : "";
        this.passwordValida = pass != null ? pass.trim() : "";
    }

    public void actualizarFramePantalla(byte[] frame) {
        synchronized (frameLock) {
            this.ultimoFramePantalla = frame;
            secuenciaPantalla++;
            frameLock.notifyAll();
        }
    }

    public void actualizarFrameCamara(byte[] frame) {
        synchronized (frameLock) {
            this.ultimoFrameCamara = frame;
            secuenciaCamara++;
            frameLock.notifyAll();
        }
    }

    // Método requerido por CameraService.java
    public void detenerAudio() {
        audioStreamManager.detenerCaptura();
    }

    private boolean estaAutenticado(IHTTPSession session) {
        if (usuarioValido.isEmpty() || passwordValida.isEmpty()) {
            return true;
        }

        String authHeader = session.getHeaders().get("authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String base64Creds = authHeader.substring(6).trim();
                String credenciales = new String(Base64.decode(base64Creds, Base64.DEFAULT));
                String[] partes = credenciales.split(":", 2);
                if (partes.length == 2) {
                    return usuarioValido.equals(partes[0]) && passwordValida.equals(partes[1]);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    // Generador de MJPEG de baja latencia.
    //
    // Importante: no usa un sleep fijo ni reenvía el mismo frame. Cada cliente
    // espera a que llegue un frame NUEVO y, si el productor va más rápido,
    // salta directamente al último frame disponible.
    private InputStream crearMJPEGStream(final boolean esCamara) {
        return new InputStream() {
            private ByteArrayInputStream currentFrameStream;
            private long ultimaSecuencia = -1L;
            private boolean cerrado;

            @Override
            public int read() throws IOException {
                if (currentFrameStream == null || currentFrameStream.available() == 0) {
                    if (!cargarSiguienteFrame()) return -1;
                }
                return currentFrameStream.read();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (b == null) throw new NullPointerException("b");
                if (off < 0 || len < 0 || len > b.length - off) {
                    throw new IndexOutOfBoundsException();
                }
                if (len == 0) return 0;

                if (currentFrameStream == null || currentFrameStream.available() == 0) {
                    if (!cargarSiguienteFrame()) return -1;
                }
                return currentFrameStream.read(b, off, len);
            }

            private boolean cargarSiguienteFrame() {
                synchronized (frameLock) {
                    while (!cerrado) {
                        byte[] frame = esCamara ? ultimoFrameCamara : ultimoFramePantalla;
                        long secuencia = esCamara ? secuenciaCamara : secuenciaPantalla;

                        if (frame != null && frame.length > 0 && secuencia != ultimaSecuencia) {
                            // Copiamos la referencia, no el contenido: el productor
                            // reemplaza arrays completos y nunca los modifica.
                            ultimaSecuencia = secuencia;

                            String header =
                                    "--frame\r\n" +
                                    "Content-Type: image/jpeg\r\n" +
                                    "Content-Length: " + frame.length + "\r\n" +
                                    "Cache-Control: no-cache, no-store\r\n\r\n";

                            ByteArrayOutputStream baos =
                                    new ByteArrayOutputStream(header.length() + frame.length + 2);
                            try {
                                baos.write(header.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                                baos.write(frame);
                                baos.write('\r');
                                baos.write('\n');
                            } catch (IOException impossible) {
                                return false;
                            }

                            currentFrameStream = new ByteArrayInputStream(baos.toByteArray());
                            return true;
                        }

                        try {
                            // Despierta inmediatamente cuando llega un frame nuevo.
                            frameLock.wait(1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return false;
                }
            }

            @Override
            public void close() throws IOException {
                cerrado = true;
                synchronized (frameLock) {
                    frameLock.notifyAll();  // Despierta al hilo que espera para que cierre rápido
                }
                super.close();
            }
        };
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (!estaAutenticado(session)) {
            Response response = newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED, 
                    "text/plain", 
                    "Acceso Denegado."
            );
            response.addHeader("WWW-Authenticate", "Basic realm=\"Acceso Restringido\"");
            return response;
        }

        String uri = session.getUri();

        // 1. STREAM MJPEG DE PANTALLA EN VIVO
        if ("/screen_stream".equals(uri)) {
            Response response = newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    crearMJPEGStream(false)
            );
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            response.addHeader("Connection", "keep-alive");
            response.addHeader("X-Accel-Buffering", "no");
            return response;
        }

        // 2. STREAM MJPEG DE CÁMARA EN VIVO
        if ("/camera_stream".equals(uri)) {
            Response response = newChunkedResponse(
                    Response.Status.OK,
                    "multipart/x-mixed-replace; boundary=frame",
                    crearMJPEGStream(true)
            );
            response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.addHeader("Pragma", "no-cache");
            response.addHeader("Expires", "0");
            response.addHeader("Connection", "keep-alive");
            response.addHeader("X-Accel-Buffering", "no");
            return response;
        }

        // 3. AUDIO WAV EN VIVO
        if ("/audio.wav".equals(uri)) {
            InputStream audioStream = audioStreamManager.crearAudioStreamCliente();
            if (audioStream != null) {
                return newChunkedResponse(Response.Status.OK, "audio/wav", audioStream);
            }
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error iniciando audio");
        }

        // 4. API CONTROL DE CÁMARA
        if ("/api/camera".equals(uri)) {
            String action = session.getParms().get("action");
            if (cameraService != null) {
                if ("on".equals(action)) {
                    cameraService.iniciarCamara();
                } else if ("off".equals(action)) {
                    cameraService.detenerCamara();
                } else if ("toggle".equals(action)) {
                    cameraService.alternarCamara();
                }
            }
            return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"status\":\"ok\"}");
        }

        // 5. PANEL INTERFAZ WEB
        String html = "<!DOCTYPE html>"
                + "<html>"
                + "<head>"
                + "<title>Panel de Monitoreo</title>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"
                + "<style>"
                + "body { background-color: #121212; color: #ffffff; font-family: Arial, sans-serif; text-align: center; margin: 0; padding: 15px; }"
                + "h1 { color: #00E676; margin-bottom: 15px; font-size: 22px; }"
                + ".container { display: flex; flex-wrap: wrap; justify-content: center; gap: 15px; }"
                
                + ".card { background: #1e1e1e; padding: 12px; border-radius: 10px; border: 1px solid #333; "
                + "        resize: both; overflow: auto; min-width: 280px; min-height: 250px; width: 440px; height: 350px; "
                + "        display: flex; flex-direction: column; justify-content: space-between; box-shadow: 0 4px 10px rgba(0,0,0,0.5); }"
                
                + ".card-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }"
                + ".card-header h3 { margin: 0; font-size: 15px; color: #00E676; }"
                
                + ".video-wrapper { flex: 1; display: flex; align-items: center; justify-content: center; background: #000; "
                + "                 overflow: hidden; border-radius: 6px; position: relative; width: 100%; height: 100%; }"
                
                + "img.stream { max-width: 100%; max-height: 100%; object-fit: contain; transition: transform 0.2s ease; }"
                
                + "button { padding: 8px 12px; margin: 2px; border: none; border-radius: 5px; font-weight: bold; cursor: pointer; color: white; font-size: 13px; }"
                + ".btn-on { background-color: #00E676; color: #000; }"
                + ".btn-off { background-color: #FF1744; }"
                + ".btn-toggle { background-color: #29B6F6; color: #000; }"
                + ".btn-tool { background-color: #424242; color: #fff; }"
                + ".btn-tool:hover { background-color: #616161; }"
                + ".btn-audio { background-color: #AA00FF; color: #fff; width: 100%; padding: 10px; font-size: 14px; margin-top: 10px; }"
                + "</style>"
                + "</head>"
                + "<body>"
                
                + "<h1>Panel de Control de Monitoreo</h1>"
                + "<div class='container'>"

                // VENTANA 1: PANTALLA
                + "<div class='card' id='cardScreen'>"
                + "  <div class='card-header'>"
                + "    <h3>Transmisión de Pantalla</h3>"
                + "    <div>"
                + "      <button class='btn-tool' onclick=\"rotarImagen('screenImg')\">🔄 90°</button>"
                + "      <button class='btn-tool' onclick=\"pantallaCompleta('cardScreen')\">⛶ Max</button>"
                + "    </div>"
                + "  </div>"
                + "  <div class='video-wrapper'>"
                + "    <img id='screenImg' class='stream' src='/screen_stream' alt='Cargando Transmisión...'>"
                + "  </div>"
                + "</div>"

                // VENTANA 2: CÁMARA
                + "<div class='card' id='cardCamera'>"
                + "  <div class='card-header'>"
                + "    <h3>Cámara en Vivo</h3>"
                + "    <div>"
                + "      <button class='btn-tool' onclick=\"rotarImagen('cameraImg')\">🔄 90°</button>"
                + "      <button class='btn-tool' onclick=\"pantallaCompleta('cardCamera')\">⛶ Max</button>"
                + "    </div>"
                + "  </div>"
                + "  <div class='video-wrapper'>"
                + "    <img id='cameraImg' class='stream' src='/camera_stream' alt='Cámara Apagada'>"
                + "  </div>"
                + "  <div style='margin-top: 8px;'>"
                + "    <button class='btn-on' onclick=\"fetch('/api/camera?action=on')\">Encender</button>"
                + "    <button class='btn-off' onclick=\"fetch('/api/camera?action=off')\">Apagar</button>"
                + "    <button class='btn-toggle' onclick=\"fetch('/api/camera?action=toggle')\">Cambiar Cámara</button>"
                + "  </div>"
                + "</div>"

                // VENTANA 3: AUDIO
                + "<div class='card' style='height: auto; min-height: 180px;'>"
                + "  <div class='card-header'>"
                + "    <h3>Audio del Micrófono</h3>"
                + "  </div>"
                + "  <p style='font-size: 13px; color: #ccc; margin: 5px 0;'>Escucha el entorno en tiempo real.</p>"
                + "  <audio id='audioPlayer'></audio>"
                + "  <button id='audioBtn' class='btn-audio' onclick='toggleAudio()'>▶ Escuchar Micrófono</button>"
                + "</div>"

                + "</div>" // Fin container

                + "<script>"
                + "  var rotaciones = { 'screenImg': 0, 'cameraImg': 0 };"
                + "  var audioPlayer = document.getElementById('audioPlayer');"
                + "  var audioBtn = document.getElementById('audioBtn');"
                + "  var listening = false;"

                + "  function rotarImagen(id) {"
                + "    rotaciones[id] = (rotaciones[id] + 90) % 360;"
                + "    document.getElementById(id).style.transform = 'rotate(' + rotaciones[id] + 'deg)';"
                + "  }"

                + "  function pantallaCompleta(cardId) {"
                + "    var elem = document.getElementById(cardId);"
                + "    if (!document.fullscreenElement && !document.webkitFullscreenElement) {"
                + "      if (elem.requestFullscreen) { elem.requestFullscreen(); }"
                + "      else if (elem.webkitRequestFullscreen) { elem.webkitRequestFullscreen(); }"
                + "    } else {"
                + "      if (document.exitFullscreen) { document.exitFullscreen(); }"
                + "      else if (document.webkitExitFullscreen) { document.webkitExitFullscreen(); }"
                + "    }"
                + "  }"

                + "  function toggleAudio() {"
                + "    if(!listening) {"
                + "      audioPlayer.src = '/audio.wav?' + Date.now();"
                + "      audioPlayer.play();"
                + "      audioBtn.innerText = '⏹ Detener Audio';"
                + "      audioBtn.style.backgroundColor = '#FF1744';"
                + "      listening = true;"
                + "    } else {"
                + "      audioPlayer.pause();"
                + "      audioPlayer.src = '';"
                + "      audioBtn.innerText = '▶ Escuchar Micrófono';"
                + "      audioBtn.style.backgroundColor = '#AA00FF';"
                + "      listening = false;"
                + "    }"
                + "  }"
                + "</script>"
                + "</body>"
                + "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }
}
