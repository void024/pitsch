package com.pitsch.backend.files;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** clamd INSTREAM client (https://docs.clamav.net/manual/Usage/Scanning.html#clamd). Fails closed on errors. */
public class ClamAvScanner implements MalwareScanner {

    private static final int CHUNK = 64 * 1024;
    private final String host;
    private final int port;

    public ClamAvScanner(String host, int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public Result scan(byte[] bytes) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30_000);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
            for (int off = 0; off < bytes.length; off += CHUNK) {
                int len = Math.min(CHUNK, bytes.length - off);
                out.writeInt(len);
                out.write(bytes, off, len);
            }
            out.writeInt(0);
            out.flush();
            String reply = readReply(socket.getInputStream());
            if (reply.endsWith("OK")) {
                return new Result(Verdict.CLEAN, null);
            }
            if (reply.endsWith("FOUND")) {
                String sig = reply.replaceFirst("^stream:\\s*", "").replaceFirst("\\s*FOUND$", "");
                return new Result(Verdict.INFECTED, sig);
            }
            return new Result(Verdict.ERROR, null);
        } catch (IOException e) {
            return new Result(Verdict.ERROR, null);
        }
    }

    private static String readReply(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1 && b != 0 && buf.size() < 1024) {
            buf.write(b);
        }
        return buf.toString(StandardCharsets.US_ASCII).trim();
    }

    @Override
    public String name() {
        return "clamav";
    }
}
