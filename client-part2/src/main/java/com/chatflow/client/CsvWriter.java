package com.chatflow.client;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class CsvWriter implements AutoCloseable {
    private static final String POISON = "__POISON__";

    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Thread writerThread;
    private final BufferedWriter writer;

    public CsvWriter(String path, String header) throws Exception {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        this.writer = new BufferedWriter(new FileWriter(file));
        if (header != null && !header.isBlank()) {
            writer.write(header);
            writer.newLine();
            writer.flush();
        }
        this.writerThread = new Thread(this::runWriter, "csv-writer");
        this.writerThread.start();
    }

    public void writeLine(String line) {
        if (line == null) {
            return;
        }
        queue.offer(line);
    }

    private void runWriter() {
        try {
            while (true) {
                String line = queue.take();
                if (POISON.equals(line)) {
                    break;
                }
                writer.write(line);
                writer.newLine();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        } finally {
            try {
                writer.flush();
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void close() {
        queue.offer(POISON);
        try {
            writerThread.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
