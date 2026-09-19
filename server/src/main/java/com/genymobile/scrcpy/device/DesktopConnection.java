package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.control.ControlChannel;
import com.genymobile.scrcpy.util.IO;
import com.genymobile.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME_PREFIX = "scrcpy";

    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;

    private final LocalSocket audioSocket;
    private final FileDescriptor audioFd;

    private final LocalSocket controlSocket;
    private final ControlChannel controlChannel;
    private final Socket videoTcpSocket;
    private final Socket audioTcpSocket;
    private final Socket controlTcpSocket;
    private final OutputStream videoOutputStream;
    private final OutputStream audioOutputStream;

    private DesktopConnection(LocalSocket videoSocket, LocalSocket audioSocket, LocalSocket controlSocket)
            throws IOException {
        this(videoSocket, audioSocket, controlSocket, null, null, null);
    }

    private DesktopConnection(LocalSocket videoSocket, LocalSocket audioSocket, LocalSocket controlSocket,
            Socket videoTcpSocket, Socket audioTcpSocket, Socket controlTcpSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;
        this.videoTcpSocket = videoTcpSocket;
        this.audioTcpSocket = audioTcpSocket;
        this.controlTcpSocket = controlTcpSocket;

        videoFd = videoSocket != null ? videoSocket.getFileDescriptor() : null;
        audioFd = audioSocket != null ? audioSocket.getFileDescriptor() : null;
        videoOutputStream = videoTcpSocket != null ? videoTcpSocket.getOutputStream() : null;
        audioOutputStream = audioTcpSocket != null ? audioTcpSocket.getOutputStream() : null;
        if (controlSocket != null) {
            controlChannel = new ControlChannel(controlSocket);
        } else if (controlTcpSocket != null) {
            controlChannel = new ControlChannel(controlTcpSocket.getInputStream(),
                    controlTcpSocket.getOutputStream());
        } else {
            controlChannel = null;
        }
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    private static String getSocketName(int scid) {
        if (scid == -1) {
            // If no SCID is set, use "scrcpy" to simplify using scrcpy-server alone
            return SOCKET_NAME_PREFIX;
        }

        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        return open(scid, tunnelForward, video, audio, control, sendDummyByte, -1, null);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio,
            boolean control, boolean sendDummyByte, int tcpPort, String tcpToken) throws IOException {
        if (tcpPort > 0) {
            return openTcp(video, audio, control, tcpPort, tcpToken);
        }

        String socketName = getSocketName(scid);

        LocalSocket videoSocket = null;
        LocalSocket audioSocket = null;
        LocalSocket controlSocket = null;
        try {
            if (tunnelForward) {
                try (LocalServerSocket localServerSocket = new LocalServerSocket(socketName)) {
                    if (video) {
                        videoSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            videoSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (audio) {
                        audioSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            audioSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                    if (control) {
                        controlSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            controlSocket.getOutputStream().write(0);
                            sendDummyByte = false;
                        }
                    }
                }
            } else {
                if (video) {
                    videoSocket = connect(socketName);
                }
                if (audio) {
                    audioSocket = connect(socketName);
                }
                if (control) {
                    controlSocket = connect(socketName);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                videoSocket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            throw e;
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    private static DesktopConnection openTcp(boolean video, boolean audio, boolean control, int tcpPort,
            String tcpToken) throws IOException {
        if (tcpToken == null || tcpToken.isEmpty()) {
            throw new IOException("Direct TCP requires a session token");
        }

        byte[] expectedToken = tcpToken.getBytes(StandardCharsets.US_ASCII);
        Socket videoSocket = null;
        Socket audioSocket = null;
        Socket controlSocket = null;
        try (ServerSocket serverSocket = new ServerSocket(tcpPort, 8)) {
            if (video) {
                videoSocket = acceptAuthorized(serverSocket, expectedToken);
            }
            if (audio) {
                audioSocket = acceptAuthorized(serverSocket, expectedToken);
            }
            if (control) {
                controlSocket = acceptAuthorized(serverSocket, expectedToken);
            }
        } catch (IOException | RuntimeException e) {
            closeQuietly(videoSocket);
            closeQuietly(audioSocket);
            closeQuietly(controlSocket);
            throw e;
        }

        return new DesktopConnection(null, null, null, videoSocket, audioSocket, controlSocket);
    }

    private static Socket acceptAuthorized(ServerSocket serverSocket, byte[] expectedToken) throws IOException {
        for (;;) {
            Socket candidate = serverSocket.accept();
            boolean authorized = false;
            try {
                candidate.setTcpNoDelay(true);
                candidate.setSoTimeout(5_000);
                byte[] receivedToken = new byte[expectedToken.length];
                readFully(candidate.getInputStream(), receivedToken, 0, receivedToken.length);
                authorized = MessageDigest.isEqual(expectedToken, receivedToken);
                if (authorized) {
                    candidate.setSoTimeout(0);
                    return candidate;
                }
            } catch (IOException ignored) {
                // ignore unauthorized or incomplete handshakes and keep listening
            } finally {
                if (!authorized) {
                    closeQuietly(candidate);
                }
            }
        }
    }

    private static void readFully(InputStream input, byte[] data, int offset, int length) throws IOException {
        int read = 0;
        while (read < length) {
            int count = input.read(data, offset + read, length - read);
            if (count < 0) {
                throw new IOException("Direct TCP handshake ended early");
            }
            read += count;
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

    private LocalSocket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    public void shutdown() throws IOException {
        if (videoSocket != null) {
            videoSocket.shutdownInput();
            videoSocket.shutdownOutput();
        }
        if (audioSocket != null) {
            audioSocket.shutdownInput();
            audioSocket.shutdownOutput();
        }
        if (controlSocket != null) {
            controlSocket.shutdownInput();
            controlSocket.shutdownOutput();
        }
        shutdownTcp(videoTcpSocket);
        shutdownTcp(audioTcpSocket);
        shutdownTcp(controlTcpSocket);
    }

    private static void shutdownTcp(Socket socket) throws IOException {
        if (socket != null) {
            socket.shutdownInput();
            socket.shutdownOutput();
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
        closeQuietly(videoTcpSocket);
        closeQuietly(audioTcpSocket);
        closeQuietly(controlTcpSocket);
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in Java, no need to set '\0' explicitly

        LocalSocket localSocket = getFirstSocket();
        if (localSocket != null) {
            IO.writeFully(localSocket.getFileDescriptor(), buffer, 0, buffer.length);
        } else if (videoOutputStream != null) {
            videoOutputStream.write(buffer, 0, buffer.length);
        } else if (audioOutputStream != null) {
            audioOutputStream.write(buffer, 0, buffer.length);
        } else if (controlTcpSocket != null) {
            controlTcpSocket.getOutputStream().write(buffer, 0, buffer.length);
        } else {
            throw new IOException("No desktop connection is available");
        }
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public OutputStream getVideoOutputStream() {
        return videoOutputStream;
    }

    public OutputStream getAudioOutputStream() {
        return audioOutputStream;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
