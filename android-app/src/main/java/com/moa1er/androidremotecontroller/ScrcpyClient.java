package com.moa1er.androidremotecontroller;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ScrcpyClient implements Closeable {
    private static final String TAG = "ScrcpyClient";
    private static final long SHELL_COMMAND_TIMEOUT_MS = 10_000L;
    // keep the stream within the range used by comparable Android clients. A
    // a lower bitrate reduces Wi-Fi and ADB queueing without changing resolution.
    private static final int VIDEO_BIT_RATE = 4_000_000;
    private static final int VIDEO_MAX_FPS = 60;
    private static final float TOUCH_MOVE_MIN_DELTA = 3f;
    private static final int VIDEO_MIN_AUTO_SIZE = 720;
    private static final int VIDEO_AUTO_STEP = 160;
    private static final long VIDEO_SLOW_PACKET_MILLIS = 350L;
    private static final long VIDEO_UPGRADE_STABLE_MILLIS = 30_000L;
    private static final long VIDEO_RESOLUTION_COOLDOWN_MILLIS = 5_000L;
    interface Listener {
        void onConnected(int width, int height);
        void onVideoSizeChanged(int width, int height);
        void onVideoStarted();
        void onStatus(int messageId, Object... arguments);
        void onError(Throwable error);
        void onDisconnected();
    }

    interface AppListListener {
        void onApps(List<String> packages);
        void onError(Throwable error);
    }

    interface ActionListener {
        void onSuccess();
        void onError(Throwable error);
    }

    private static final String SERVER_PATH = "/data/local/tmp/scrcpy-server.jar";
    private final InputStream serverAsset;
    private final Listener listener;
    private final AdbAuthKey authKey;
    private final boolean directTcp;
    private final int directTcpPort;
    private final boolean useH265;
    private final boolean automaticResolution;
    private final int configuredMaxSize;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Object lifecycleLock = new Object();
    private final ExecutorService controlExecutor = Executors.newSingleThreadExecutor(runnable ->
            new Thread(() -> {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
                } catch (RuntimeException ignored) {
                    // control input can still make progress with its inherited priority.
                }
                runnable.run();
            }, "scrcpy-control"));
    private final Object touchLock = new Object();
    private TouchEvent pendingMove;
    private boolean moveDrainQueued;
    private float lastTouchX;
    private float lastTouchY;
    private boolean touchActive;
    private volatile AdbTransport adb;
    // keep control traffic independent from the high-volume video transport.
    private volatile AdbTransport controlAdb;
    private volatile AdbTransport.AdbStream shell;
    private volatile AdbTransport.AdbStream video;
    private volatile AdbTransport.AdbStream control;
    private volatile Socket directVideoSocket;
    private volatile Socket directControlSocket;
    private volatile InputStream videoInput;
    private volatile ControlWriter controlWriter;

    ScrcpyClient(InputStream serverAsset, AdbAuthKey authKey, boolean directTcp, int directTcpPort,
            boolean useH265, boolean automaticResolution, int configuredMaxSize, Listener listener) {
        this.serverAsset = serverAsset;
        this.authKey = authKey;
        this.directTcp = directTcp;
        this.directTcpPort = directTcpPort;
        this.useH265 = useH265;
        this.automaticResolution = automaticResolution;
        this.configuredMaxSize = configuredMaxSize;
        this.listener = listener;
    }

    void connect(String host, int port, Surface surface) {
        new Thread(() -> {
            try {
                listener.onStatus(R.string.status_connecting, host, port);
                AdbTransport transport = new AdbTransport();
                synchronized (lifecycleLock) {
                    if (stopped.get()) {
                        transport.close();
                        return;
                    }
                    adb = transport;
                }
                transport.connect(host, port, authKey);
                ensureActive();
                listener.onStatus(R.string.status_pushing_server);
                pushServer(transport);

                int scid = (int) (System.nanoTime() & 0x7fffffff);
                String socketName = "scrcpy_" + String.format(Locale.US, "%08x", scid);
                String tcpToken = directTcp ? createSessionToken() : null;
                String videoCodec = useH265
                        && findHardwareDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC) != null
                        ? "h265" : "h264";
                String command = "CLASSPATH=" + SERVER_PATH + " app_process / "
                        + "com.genymobile.scrcpy.Server 4.1"
                        + " scid=" + String.format(Locale.US, "%08x", scid)
                        + (directTcp
                        ? " tunnel_forward=false tcp_port=" + directTcpPort + " tcp_token=" + tcpToken
                        : " tunnel_forward=true")
                        + " video_codec=" + videoCodec + " video_bit_rate=" + VIDEO_BIT_RATE
                        + " max_size=" + configuredMaxSize + " max_fps=" + VIDEO_MAX_FPS
                        + " audio=false control=true send_dummy_byte=false"
                        + " send_device_meta=false send_stream_meta=true send_frame_meta=true"
                        + " power_on=true power_off_on_close=true cleanup=true";
                AdbTransport.AdbStream openedShell = transport.open("shell:" + command);
                adoptShell(openedShell);
                Thread shellDrain = new Thread(this::drainShell, "scrcpy-shell-drain");
                shellDrain.start();

                listener.onStatus(R.string.status_opening_streams);
                InputStream openedVideoInput;
                if (directTcp) {
                    Socket openedVideo = openDirectSocket(host, directTcpPort, tcpToken);
                    adoptDirectVideo(openedVideo);
                    openedVideoInput = openedVideo.getInputStream();
                    Socket openedControl = openDirectSocket(host, directTcpPort, tcpToken);
                    adoptDirectControl(openedControl);
                } else {
                    AdbTransport.AdbStream openedVideo = openLocalSocket(transport, socketName);
                    adoptVideo(openedVideo);
                    openedVideoInput = new AdbInputStream(openedVideo);
                    AdbTransport separateControlTransport = new AdbTransport(true);
                    adoptControlAdb(separateControlTransport);
                    separateControlTransport.connect(host, port, authKey);
                    AdbTransport.AdbStream openedControl = openLocalSocket(
                            separateControlTransport, socketName);
                    adoptControl(openedControl);
                }

                videoInput = openedVideoInput;
                int codecId = readIntBE(openedVideoInput);
                String videoMimeType;
                if (codecId == 0x68323634) {
                    videoMimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
                } else if (codecId == 0x68323635) {
                    videoMimeType = MediaFormat.MIMETYPE_VIDEO_HEVC;
                } else {
                    throw new IOException("Target returned unsupported video codec: 0x"
                            + Integer.toHexString(codecId));
                }
                byte[] session = new byte[12];
                readFully(openedVideoInput, session, 0, session.length);
                if ((session[0] & 0x80) == 0) {
                    throw new IOException("Target did not send a scrcpy video session header");
                }
                int width = readIntBE(session, 4);
                int height = readIntBE(session, 8);
                AdbLimits.videoPixels(width, height);
                listener.onConnected(width, height);

                String selectedVideoMimeType = videoMimeType;
                new Thread(() -> decodeVideo(surface, width, height, openedVideoInput,
                        selectedVideoMimeType), "scrcpy-video").start();
            } catch (Throwable error) {
                fail(error);
            }
        }, "scrcpy-connect").start();
    }

    private void ensureActive() throws IOException {
        if (stopped.get()) {
            throw new IOException("Connection stopped");
        }
    }

    private void adoptShell(AdbTransport.AdbStream stream) throws IOException {
        synchronized (lifecycleLock) {
            if (stopped.get()) {
                closeQuietly(stream);
                throw new IOException("Connection stopped");
            }
            shell = stream;
        }
    }

    private void adoptVideo(AdbTransport.AdbStream stream) throws IOException {
        synchronized (lifecycleLock) {
            if (stopped.get()) {
                closeQuietly(stream);
                throw new IOException("Connection stopped");
            }
            video = stream;
        }
    }

    private void adoptDirectVideo(Socket socket) throws IOException {
        synchronized (lifecycleLock) {
            if (stopped.get()) {
                closeQuietly(socket);
                throw new IOException("Connection stopped");
            }
            directVideoSocket = socket;
        }
    }

    private void adoptDirectControl(Socket socket) throws IOException {
        synchronized (lifecycleLock) {
            if (stopped.get()) {
                closeQuietly(socket);
                throw new IOException("Connection stopped");
            }
            directControlSocket = socket;
            controlWriter = new ControlWriter(socket.getOutputStream());
        }
    }

    private void adoptControlAdb(AdbTransport transport) throws IOException {
        synchronized (lifecycleLock) {
            if (stopped.get()) {
                transport.close();
                throw new IOException("Connection stopped");
            }
            controlAdb = transport;
        }
    }

    private void adoptControl(AdbTransport.AdbStream stream) throws IOException {
        synchronized (lifecycleLock) {
            if (stopped.get()) {
                closeQuietly(stream);
                throw new IOException("Connection stopped");
            }
            control = stream;
            controlWriter = new ControlWriter(stream);
        }
    }

    private void pushServer(AdbTransport transport) throws IOException {
        try (AdbTransport.AdbStream sync = transport.open("sync:")) {
            byte[] path = (SERVER_PATH + ",33204").getBytes(StandardCharsets.UTF_8);
            sendSync(sync, "SEND", path);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = serverAsset.read(buffer)) != -1) {
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                sendSync(sync, "DATA", chunk);
            }
            byte[] done = new byte[12];
            byte[] doneId = "DONE".getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(doneId, 0, done, 0, doneId.length);
            writeIntLE(done, 4, 0);
            writeIntLE(done, 8, 33204);
            sync.write(done);

            byte[] result = new byte[8];
            sync.readFully(result, 0, result.length);
            String status = new String(result, 0, 4, StandardCharsets.US_ASCII);
            int length = readIntLE(result, 4);
            if (!"OKAY".equals(status)) {
                if (length < 0 || length > 4096) {
                    throw new IOException("ADB push returned an invalid error length: " + length);
                }
                byte[] message = new byte[length];
                sync.readFully(message, 0, message.length);
                throw new IOException("ADB push failed: " + new String(message, StandardCharsets.UTF_8));
            }
        }
    }

    private AdbTransport.AdbStream openLocalSocket(AdbTransport transport, String socketName) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < 30 && !stopped.get(); ++attempt) {
            try {
                return transport.open("localabstract:" + socketName);
            } catch (IOException error) {
                lastError = error;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while opening scrcpy socket", interrupted);
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Connection stopped");
    }

    private Socket openDirectSocket(String host, int port, String token) throws IOException {
        IOException lastError = null;
        byte[] tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        for (int attempt = 0; attempt < 60 && !stopped.get(); ++attempt) {
            Socket socket = new Socket();
            try {
                socket.setTcpNoDelay(true);
                socket.connect(new InetSocketAddress(host, port), 2_000);
                OutputStream outputStream = socket.getOutputStream();
                outputStream.write(tokenBytes);
                outputStream.flush();
                return socket;
            } catch (IOException error) {
                lastError = error;
                closeQuietly(socket);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while opening direct TCP socket", interrupted);
                }
            }
        }
        throw lastError != null ? lastError : new IOException("Connection stopped");
    }

    private static String createSessionToken() {
        byte[] token = new byte[24];
        new SecureRandom().nextBytes(token);
        StringBuilder result = new StringBuilder(token.length * 2);
        for (byte value : token) {
            result.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static void sendSync(AdbTransport.AdbStream stream, String id, byte[] payload) throws IOException {
        byte[] message = new byte[8 + payload.length];
        byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, message, 0, 4);
        writeIntLE(message, 4, payload.length);
        System.arraycopy(payload, 0, message, 8, payload.length);
        stream.write(message);
    }

    private void drainShell() {
        try {
            while (!stopped.get()) {
                shell.readChunk();
            }
        } catch (IOException error) {
            if (!stopped.get()) {
                fail(new IOException("The scrcpy server shell stopped", error));
            }
        }
    }

    private void decodeVideo(Surface surface, int width, int height, InputStream videoStream,
            String videoMimeType) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
        } catch (RuntimeException ignored) {
            // a decoder thread can still make progress with its inherited priority.
        }
        AsyncVideoDecoder decoder = null;
        AtomicBoolean firstFrameReported = new AtomicBoolean();
        Throwable failure = null;
        int currentMaxSize = configuredMaxSize;
        int slowPacketCount = 0;
        long stableSince = SystemClock.uptimeMillis();
        long lastResolutionChange = 0L;
        try {
            decoder = new AsyncVideoDecoder(surface, width, height, videoMimeType, () -> {
                if (firstFrameReported.compareAndSet(false, true)) {
                    listener.onVideoStarted();
                    listener.onStatus(R.string.status_video_rendering, width, height);
                }
            });
            byte[] header = new byte[12];
            byte[] packet = new byte[0];
            while (!stopped.get()) {
                long packetStart = SystemClock.uptimeMillis();
                readFully(videoStream, header, 0, header.length);
                long ptsAndFlags = readLongBE(header, 0);
                int length = readIntBE(header, 8);

                if ((ptsAndFlags & Long.MIN_VALUE) != 0) {
                    int newWidth = (int) ptsAndFlags;
                    int newHeight = length;
                    AdbLimits.videoPixels(newWidth, newHeight);
                    decoder.close();
                    decoder = new AsyncVideoDecoder(surface, newWidth, newHeight, videoMimeType, () -> {
                        if (firstFrameReported.compareAndSet(false, true)) {
                            listener.onVideoStarted();
                            listener.onStatus(R.string.status_video_rendering, newWidth, newHeight);
                        }
                    });
                    listener.onVideoSizeChanged(newWidth, newHeight);
                    slowPacketCount = 0;
                    stableSince = SystemClock.uptimeMillis();
                    packet = new byte[0];
                    continue;
                }

                AdbLimits.checkVideoPacketLength(length);
                if (packet.length < length) {
                    packet = new byte[length];
                }
                readFully(videoStream, packet, 0, length);
                long packetReadMillis = SystemClock.uptimeMillis() - packetStart;
                if (automaticResolution) {
                    if (packetReadMillis >= VIDEO_SLOW_PACKET_MILLIS) {
                        ++slowPacketCount;
                    } else {
                        slowPacketCount = 0;
                    }
                    long now = SystemClock.uptimeMillis();
                    if (slowPacketCount >= 5 && currentMaxSize > VIDEO_MIN_AUTO_SIZE
                            && now - lastResolutionChange >= VIDEO_RESOLUTION_COOLDOWN_MILLIS) {
                        currentMaxSize = Math.max(VIDEO_MIN_AUTO_SIZE,
                                currentMaxSize - VIDEO_AUTO_STEP);
                        requestVideoMaxSize(currentMaxSize);
                        slowPacketCount = 0;
                        stableSince = now;
                        lastResolutionChange = now;
                    } else if (slowPacketCount == 0
                            && currentMaxSize < configuredMaxSize
                            && now - stableSince >= VIDEO_UPGRADE_STABLE_MILLIS
                            && now - lastResolutionChange >= VIDEO_RESOLUTION_COOLDOWN_MILLIS) {
                        currentMaxSize = Math.min(configuredMaxSize,
                                currentMaxSize + VIDEO_AUTO_STEP);
                        requestVideoMaxSize(currentMaxSize);
                        stableSince = now;
                        lastResolutionChange = now;
                    }
                }

                int flags = (ptsAndFlags & (1L << 62)) != 0 ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0;
                long ptsUs = ptsAndFlags & ((1L << 61) - 1);
                decoder.queue(packet, length, ptsUs, flags);
            }
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (decoder != null) {
                decoder.close();
            }
            if (failure != null) {
                fail(failure);
            } else if (!stopped.get()) {
                fail(new IOException("The scrcpy video stream stopped"));
            }
        }
    }

    private static MediaCodec createDecoder(Surface surface, int width, int height,
            String videoMimeType,
            MediaCodec.Callback callback, Handler callbackHandler) throws IOException {
        MediaFormat format = MediaFormat.createVideoFormat(
                videoMimeType, width, height);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }

        String decoderName = findHardwareDecoder(videoMimeType);
        MediaCodec decoder = null;
        try {
            decoder = decoderName == null
                    ? MediaCodec.createDecoderByType(videoMimeType)
                    : MediaCodec.createByCodecName(decoderName);
            decoder.setCallback(callback, callbackHandler);
            decoder.configure(format, surface, null, 0);
            decoder.start();
            return decoder;
        } catch (Exception error) {
            releaseDecoder(decoder);
            try {
                decoder = MediaCodec.createDecoderByType(videoMimeType);
                decoder.setCallback(callback, callbackHandler);
                decoder.configure(format, surface, null, 0);
                decoder.start();
                return decoder;
            } catch (Exception fallbackError) {
                releaseDecoder(decoder);
                fallbackError.addSuppressed(error);
                throw new IOException("Could not configure the video decoder", fallbackError);
            }
        }
    }

    private static String findHardwareDecoder(String videoMimeType) {
        String fallback = null;
        try {
            MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
                if (codecInfo.isEncoder()) {
                    continue;
                }
                boolean supportsAvc = false;
                for (String type : codecInfo.getSupportedTypes()) {
                        if (videoMimeType.equalsIgnoreCase(type)) {
                        supportsAvc = true;
                        break;
                    }
                }
                if (!supportsAvc) {
                    continue;
                }

                String name = codecInfo.getName();
                String lowerName = name.toLowerCase(Locale.US);
                if (lowerName.startsWith("omx.google")
                        || lowerName.startsWith("c2.android")
                        || lowerName.contains("software")) {
                    continue;
                }
                if (lowerName.contains("low_latency")) {
                    return name;
                }
                if (fallback == null || (lowerName.contains("c2")
                        && !fallback.toLowerCase(Locale.US).contains("c2"))) {
                    fallback = name;
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return fallback;
    }

    private static void releaseDecoder(MediaCodec decoder) {
        if (decoder == null) {
            return;
        }
        try {
            decoder.stop();
        } catch (Exception ignored) {
        }
        try {
            decoder.release();
        } catch (Exception ignored) {
        }
    }

    private final class AsyncVideoDecoder implements Closeable {
        private final HandlerThread callbackThread =
                new HandlerThread("scrcpy-decode", Process.THREAD_PRIORITY_DISPLAY);
        private final Handler callbackHandler;
        private final BlockingQueue<Integer> inputBuffers = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable onFirstFrame;
        private volatile Throwable failure;
        private MediaCodec decoder;

        AsyncVideoDecoder(Surface surface, int width, int height, String videoMimeType,
                Runnable onFirstFrame)
                throws IOException {
            this.onFirstFrame = onFirstFrame;
            callbackThread.start();
            callbackHandler = new Handler(callbackThread.getLooper());
            MediaCodec.Callback callback = new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    if (!closed.get()) {
                        inputBuffers.offer(index);
                    }
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index,
                        MediaCodec.BufferInfo info) {
                    if (closed.get()) {
                        return;
                    }
                    try {
                        codec.releaseOutputBuffer(index, true);
                        if (info.size > 0) {
                            onFirstFrame.run();
                        }
                    } catch (Throwable error) {
                        setFailure(error);
                    }
                }

                @Override
                public void onError(MediaCodec codec, MediaCodec.CodecException error) {
                    setFailure(error);
                }

                @Override
                public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
                }
            };
            try {
                decoder = createDecoder(surface, width, height, videoMimeType, callback,
                        callbackHandler);
            } catch (IOException | RuntimeException error) {
                close();
                throw error;
            }
        }

        void queue(byte[] data, int length, long ptsUs, int flags) throws IOException {
            while (!closed.get()) {
                checkFailure();
                try {
                    Integer index = inputBuffers.poll(100, TimeUnit.MILLISECONDS);
                    if (index == null) {
                        continue;
                    }
                    java.nio.ByteBuffer input = decoder.getInputBuffer(index);
                    if (input == null || input.capacity() < length) {
                        throw new IOException("MediaCodec input buffer is too small");
                    }
                    input.clear();
                    input.put(data, 0, length);
                    decoder.queueInputBuffer(index, 0, length, ptsUs, flags);
                    return;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while queueing video", interrupted);
                } catch (IllegalStateException error) {
                    setFailure(error);
                    checkFailure();
                }
            }
            throw new IOException("Video decoder stopped");
        }

        private void setFailure(Throwable error) {
            if (failure == null) {
                failure = error;
            }
        }

        private void checkFailure() throws IOException {
            Throwable error = failure;
            if (error != null) {
                throw new IOException("Video decoder failed", error);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            inputBuffers.clear();
            releaseDecoder(decoder);
            callbackThread.quitSafely();
        }
    }

    void touch(int action, float x, float y, float pressure, int width, int height) {
        ControlWriter writer = controlWriter;
        if (writer == null) {
            return;
        }
        synchronized (touchLock) {
            if (action == MotionEvent.ACTION_DOWN) {
                touchActive = true;
                lastTouchX = x;
                lastTouchY = y;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (touchActive && Math.abs(x - lastTouchX) < TOUCH_MOVE_MIN_DELTA
                        && Math.abs(y - lastTouchY) < TOUCH_MOVE_MIN_DELTA) {
                    return;
                }
                lastTouchX = x;
                lastTouchY = y;
            } else if (action == MotionEvent.ACTION_UP) {
                touchActive = false;
            }
        }
        TouchEvent event = new TouchEvent(action, x, y, pressure, width, height);
        if (directTcp) {
            // direct sockets do not wait for an ADB write acknowledgement, so keep every
            // accepted move instead of coalescing it behind the ADB safety path.
            enqueueControl(() -> writer.touch(event.action, event.x, event.y,
                    event.pressure, event.width, event.height));
            return;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            // do not let delayed ADB acknowledgements build a stale move backlog.
            synchronized (touchLock) {
                pendingMove = event;
                if (moveDrainQueued) {
                    return;
                }
                moveDrainQueued = true;
            }
            enqueueControl(() -> drainTouchMoves(writer));
            return;
        }
        enqueueControl(() -> writer.touch(action, x, y, pressure, width, height));
    }

    private void drainTouchMoves(ControlWriter writer) throws IOException {
        for (;;) {
            TouchEvent event;
            synchronized (touchLock) {
                event = pendingMove;
                pendingMove = null;
                if (event == null) {
                    moveDrainQueued = false;
                    return;
                }
            }
            writer.touch(event.action, event.x, event.y, event.pressure, event.width, event.height);
        }
    }

    private static final class TouchEvent {
        final int action;
        final float x;
        final float y;
        final float pressure;
        final int width;
        final int height;

        TouchEvent(int action, float x, float y, float pressure, int width, int height) {
            this.action = action;
            this.x = x;
            this.y = y;
            this.pressure = pressure;
            this.width = width;
            this.height = height;
        }
    }

    void back() {
        ControlWriter writer = controlWriter;
        if (writer == null) {
            return;
        }
        enqueueControl(writer::sendBack);
    }

    void wake() {
        ControlWriter writer = controlWriter;
        if (writer == null) {
            return;
        }
        enqueueControl(writer::wake);
    }

    private void requestVideoMaxSize(int maxSize) {
        ControlWriter writer = controlWriter;
        if (writer != null) {
            enqueueControl(() -> writer.setVideoMaxSize(maxSize));
        }
    }

    void listInstalledApps(AppListListener listener) {
        new Thread(() -> {
            try {
                String output = runShellCommand("pm list packages -3");
                List<String> packages = new ArrayList<>();
                for (String line : output.split("\\r?\\n")) {
                    if (line.startsWith("package:")) {
                        String packageName = line.substring("package:".length()).trim();
                        if (AndroidClientValidation.isValidPackageName(packageName)) {
                            packages.add(packageName);
                        }
                    }
                }
                Collections.sort(packages);
                listener.onApps(packages);
            } catch (Throwable error) {
                listener.onError(error);
            }
        }, "scrcpy-list-apps").start();
    }

    void launchApp(String packageName, ActionListener listener) {
        if (!AndroidClientValidation.isValidPackageName(packageName)) {
            listener.onError(new IOException("Invalid Android package name"));
            return;
        }
        new Thread(() -> {
            try {
                String output = runShellCommand("monkey -p " + packageName
                        + " -c android.intent.category.LAUNCHER 1");
                String lower = output.toLowerCase(Locale.US);
                if (lower.contains("no activities found") || lower.contains("monkey aborted")) {
                    throw new IOException("No launchable activity found for " + packageName);
                }
                listener.onSuccess();
            } catch (Throwable error) {
                listener.onError(error);
            }
        }, "scrcpy-launch-app").start();
    }

    private String runShellCommand(String command) throws IOException {
        AdbTransport transport = adb;
        if (transport == null || stopped.get()) {
            throw new IOException("ADB connection is not active");
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHELL_COMMAND_TIMEOUT_MS);
        AdbTransport.AdbStream stream = transport.open("shell:" + command, SHELL_COMMAND_TIMEOUT_MS);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            while (!stopped.get()) {
                long remainingMillis = AdbLimits.remainingMillis(deadline, System.nanoTime());
                if (remainingMillis == 0) {
                    throw new SocketTimeoutException("Timed out waiting for target shell command");
                }
                byte[] chunk = stream.readChunk(remainingMillis);
                AdbLimits.appendShellOutput(output, chunk);
            }
            throw new IOException("ADB connection stopped");
        } catch (EOFException end) {
            // the shell command completed and closed its ADB stream
        } finally {
            closeQuietly(stream);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private void enqueueControl(ControlAction action) {
        if (stopped.get()) {
            return;
        }
        try {
            controlExecutor.execute(() -> {
                try {
                    action.execute();
                } catch (IOException error) {
                    if (!stopped.get()) {
                        fail(error);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // A close may win the race after the stopped check.
        }
    }

    @Override
    public void close() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        controlExecutor.shutdownNow();
        new Thread(() -> {
            closeResources();
            listener.onDisconnected();
        }, "scrcpy-close").start();
    }

    private void fail(Throwable error) {
        if (stopped.get()) {
            return;
        }
        Log.e(TAG, "Connection failed", error);
        closeWithError(error);
    }

    private void closeWithError(Throwable error) {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        controlExecutor.shutdownNow();
        new Thread(() -> {
            try {
                listener.onError(error);
            } finally {
                closeResources();
                listener.onDisconnected();
            }
        }, "scrcpy-failure-close").start();
    }

    private void closeResources() {
        AdbTransport.AdbStream currentControl;
        AdbTransport.AdbStream currentVideo;
        AdbTransport.AdbStream currentShell;
        AdbTransport currentAdb;
        AdbTransport currentControlAdb;
        Socket currentDirectVideo;
        Socket currentDirectControl;
        synchronized (lifecycleLock) {
            currentControl = control;
            currentVideo = video;
            currentShell = shell;
            currentAdb = adb;
            currentControlAdb = controlAdb;
            currentDirectVideo = directVideoSocket;
            currentDirectControl = directControlSocket;
            control = null;
            video = null;
            shell = null;
            adb = null;
            controlAdb = null;
            directVideoSocket = null;
            directControlSocket = null;
            videoInput = null;
            controlWriter = null;
        }
        closeQuietly(currentControl);
        closeQuietly(currentVideo);
        closeQuietly(currentShell);
        closeQuietly(currentDirectVideo);
        closeQuietly(currentDirectControl);
        closeQuietly(serverAsset);
        if (currentAdb != null) {
            currentAdb.close();
        }
        if (currentControlAdb != null && currentControlAdb != currentAdb) {
            currentControlAdb.close();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private interface ControlAction {
        void execute() throws IOException;
    }

    private static final class AdbInputStream extends InputStream {
        private final AdbTransport.AdbStream stream;

        AdbInputStream(AdbTransport.AdbStream stream) {
            this.stream = stream;
        }

        @Override
        public int read() throws IOException {
            return stream.readUnsignedByte();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            stream.readFully(buffer, offset, length);
            return length;
        }

        @Override
        public void close() throws IOException {
            stream.close();
        }
    }

    private static void readFully(InputStream input, byte[] data, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = input.read(data, offset + read, length - read);
            if (count < 0) {
                throw new EOFException("Video stream closed");
            }
            read += count;
        }
    }

    private static int readIntBE(InputStream stream) throws IOException {
        byte[] data = new byte[4];
        readFully(stream, data, 0, 4);
        return readIntBE(data, 0);
    }

    private static int readIntBE(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    private static long readLongBE(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; ++i) {
            value = (value << 8) | (data[offset + i] & 0xffL);
        }
        return value;
    }

    private static int readIntLE(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24);
    }

    private static void writeIntLE(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >> 8);
        data[offset + 2] = (byte) (value >> 16);
        data[offset + 3] = (byte) (value >> 24);
    }
}
