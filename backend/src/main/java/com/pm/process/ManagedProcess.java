package com.pm.process;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

/** A live, managed external process with log fan-out. */
@Slf4j
public class ManagedProcess {

    /** Matches ANSI CSI / SGR escape sequences (color codes, cursor moves, etc.). */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[;?0-9]*[A-Za-z]");

    private static final Charset GBK = Charset.forName("GBK");

    @Getter private final String projectId;
    @Getter private final Process process;
    @Getter private final long pid;
    @Getter private final Instant startedAt;
    @Getter private final RingBuffer logs;
    @Getter private final Path logFile;

    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final Thread pumpThread;
    private volatile boolean closed = false;

    public ManagedProcess(String projectId, Process process, RingBuffer logs, Path logFile) {
        this.projectId = projectId;
        this.process = process;
        this.pid = process.pid();
        this.startedAt = Instant.now();
        this.logs = logs;
        this.logFile = logFile;

        this.pumpThread = new Thread(this::pumpStdout, "pm-stdout-" + projectId);
        this.pumpThread.setDaemon(true);
        this.pumpThread.start();
    }

    public void subscribe(SseEmitter emitter) {
        subscribers.add(emitter);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));

        // Replay current buffer
        for (String line : logs.snapshot()) {
            try {
                emitter.send(SseEmitter.event().name("log").data(line));
            } catch (IOException e) {
                subscribers.remove(emitter);
                return;
            }
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    private void pumpStdout() {
        try (InputStream in = process.getInputStream();
             BufferedWriter fileWriter = openLogFile()) {

            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(512);
            int b;
            while (!closed && (b = in.read()) != -1) {
                if (b == '\n') {
                    emitLine(lineBuf, fileWriter);
                } else {
                    lineBuf.write(b);
                }
            }
            if (lineBuf.size() > 0) emitLine(lineBuf, fileWriter);
        } catch (IOException e) {
            log.warn("[{}] stdout pump error: {}", projectId, e.getMessage());
        } finally {
            String exitLine = "[pm] process exited with code " + (process.isAlive() ? "?" : process.exitValue());
            logs.add(exitLine);
            broadcast(exitLine);
            subscribers.forEach(SseEmitter::complete);
            subscribers.clear();
        }
    }

    private void emitLine(ByteArrayOutputStream lineBuf, BufferedWriter fileWriter) {
        byte[] bytes = lineBuf.toByteArray();
        lineBuf.reset();
        // Drop trailing CR (CRLF terminators).
        if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
            bytes = Arrays.copyOf(bytes, bytes.length - 1);
        }
        String line = decodeSmart(bytes);
        String clean = stripAnsi(line);
        logs.add(clean);
        writeToFile(fileWriter, clean);
        broadcast(clean);
    }

    /** Try UTF-8 strict; on malformed bytes fall back to GBK (Windows CN system locale). */
    private static String decodeSmart(byte[] bytes) {
        if (bytes.length == 0) return "";
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, GBK);
        }
    }

    private BufferedWriter openLogFile() throws IOException {
        if (logFile == null) return null;
        Files.createDirectories(logFile.getParent());
        return Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void writeToFile(BufferedWriter w, String line) {
        if (w == null) return;
        try {
            w.write(DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
            w.write(" ");
            w.write(line);
            w.newLine();
            w.flush();
        } catch (IOException ignored) {}
    }

    private void broadcast(String line) {
        for (SseEmitter emitter : subscribers) {
            try {
                emitter.send(SseEmitter.event().name("log").data(line));
            } catch (IOException e) {
                subscribers.remove(emitter);
            }
        }
    }

    public void close() {
        closed = true;
        subscribers.forEach(SseEmitter::complete);
        subscribers.clear();
    }

    private static String stripAnsi(String s) {
        if (s == null || s.isEmpty()) return s;
        return ANSI_ESCAPE.matcher(s).replaceAll("");
    }
}
