package com.ctrip.zeus.logstats.tracker;

import com.ctrip.zeus.logstats.StatsDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Scanner;

/**
 * Created by zhoumy on 2015/11/13.
 */
public class AccessLogTracker implements LogTracker {
    private final int TrackLatch = 10;
    private final LogTrackerStrategy strategy;
    private final String logFilename;
    private final int size;
    private final ByteBuffer buffer;
    private RandomAccessFile raf;
    private FileChannel fileChannel;
    private long offset;
    private long previousOffset;
    private String offsetValue = "";
    private int rollingLogCounter;
    private File trackingFile;
    private boolean allowTracking;
    private int startMode;
    private byte[] line;

    Logger logger = LoggerFactory.getLogger(this.getClass());

    public AccessLogTracker(LogTrackerStrategy strategy) {
        this.strategy = strategy;
        logFilename = strategy.getLogFilename();
        size = strategy.getReadSize();
        allowTracking = strategy.isAllowTrackerMemo();
        startMode = strategy.getStartMode();
        if (allowTracking) {
            trackingFile = new File(strategy.getTrackerMemoFilename());
            if (!trackingFile.exists())
                try {
                    trackingFile.createNewFile();
                } catch (IOException e) {
                    allowTracking = false;
                    logger.error("Create access log tracking file fails.", e);
                }
        }
        buffer = ByteBuffer.allocate(size);
        line = new byte[size];
    }

    @Override
    public String getName() {
        return "AccessLogTracker";
    }

    @Override
    public String getLogFilename() {
        return logFilename;
    }

    @Override
    public LogTrackerStrategy getStrategy() {
        return strategy;
    }

    @Override
    public boolean reachFileEnd() throws IOException {
        if (raf == null) {
            // File may not be open, wait and retry get raf.
            try {
                Thread.sleep(500L);
            } catch (InterruptedException e) {
            }
        }
        if (fileChannel == null) {
            if (raf != null) {
                fileChannel = raf.getChannel();
            }
            if (fileChannel == null) {
                logger.error("Cannot reach underlying fileChannel. Reset file handlers.");
                try {
                    reset(startMode, allowTracking, false);
                } catch (Exception ex) {
                    return true;
                }
            }
        }
        return offset == fileChannel.size();
    }

    @Override
    public boolean reopenOnFileChange(String event) {
        logger.info("Reopen file on file change event " + event + ".");
        try {
            reset(LogTrackerStrategy.START_FROM_HEAD, false, false);
            return true;
        } catch (IOException e) {
            try {
                Thread.sleep(500L);
                reset(LogTrackerStrategy.START_FROM_HEAD, false, false);
                return true;
            } catch (InterruptedException e1) {
            } catch (IOException e1) {
                logger.error("Unexpected error occurred when reacting to fileChange signal " + event + ".", e1);
            }
        }
        return false;
    }

    @Override
    public void start() throws IOException {
        reset(startMode, allowTracking, false);
    }

    @Override
    public void stop() throws IOException {
        rollingLogCounter = TrackLatch;
        logIfAllowed();
        if (fileChannel != null)
            fileChannel.close();
        if (raf != null)
            raf.close();
        fileChannel = null;
        raf = null;
    }

    @Override
    public String move() throws IOException {
        return raf.readLine();
    }

    @Override
    public void fastMove(final StatsDelegate<String> delegator) throws IOException {
        try {
            if (offset > fileChannel.size()) {
                previousOffset = offset = 0;
                fileChannel.position(offset);
            }
            buffer.clear();
            try {
                if (fileChannel.read(buffer) == -1)
                    return;
            } catch (IOException ex) {
                stop();
            }
            buffer.flip();
            StringBuilder valueBuilder = new StringBuilder();
            boolean eol = false;
            int row = 0;
            int colOffset = 0;

            while (buffer.hasRemaining()) {
                while (!eol && buffer.hasRemaining()) {
                    byte b;
                    switch (b = buffer.get()) {
                        case -1:
                        case '\n':
                            eol = true;
                            offsetValue = valueBuilder.append(new String(line, 0, colOffset)).toString();
                            valueBuilder.setLength(0);
                            try {
                                delegator.delegate(offsetValue);
                            } catch (Exception ex) {
                                logger.error("AccessLogTracker::delegator throws an unexpected error", ex);
                            }
                            previousOffset = offset;
                            offset += ++colOffset;
                            row++;
                            break;
                        case '\r':
                            eol = true;
                            offsetValue = valueBuilder.append(new String(line, 0, colOffset)).toString();
                            valueBuilder.setLength(0);
                            if ((buffer.get()) != '\n') {
                                buffer.position(colOffset);
                            } else {
                                colOffset++;
                            }
                            try {
                                delegator.delegate(offsetValue);
                            } catch (Exception ex) {
                                logger.error("AccessLogTracker::delegator throws an unexpected error", ex);
                            }
                            previousOffset = offset;
                            offset += ++colOffset;
                            row++;
                            break;
                        default:
                            line[colOffset] = b;
                            ++colOffset;
                            break;
                    } // end of switch
                }// end of while !eol && buffer.hasRemaining
                // the cursor hasn't reached the end of a line, read one more buffer
                if (row == 0) {
                    buffer.clear();
                    try {
                        if (fileChannel.read(buffer) == -1) {
                            fileChannel.position(offset);
                            logIfAllowed();
                            return;
                        }
                    } catch (IOException ex) {
                        stop();
                    }
                    buffer.flip();
                    valueBuilder.append(new String(line, 0, colOffset));
                }
                colOffset = 0;
                eol = false;
            }
            fileChannel.position(offset);
            logIfAllowed();
        } catch (IOException ex) {
            // this code is never expected to be reached
            logger.error("Unexpected error occurred when tracking access.log.", ex);
            reset(LogTrackerStrategy.START_FROM_CURRENT, false, false);
        }
    }

    private void reset(int startFromOption, boolean restoreFromFile, boolean restoreFromMemory) throws IOException {
        if (!new File(getLogFilename()).exists()) {
            throw new IOException(logFilename + " is not a file or does not exist.");
        }
        if (fileChannel != null)
            fileChannel.close();
        if (raf != null)
            raf.close();

        raf = new RandomAccessFile(getLogFilename(), "r");
        fileChannel = raf.getChannel();

        if (restoreFromMemory) {
            // if (file is truncated / switched to a new file handle) refused to set the restored value
            if (fileChannel.size() < offset) {
                // fall through
            } else {
                fileChannel.position(offset);
                return;
            }
        }

        if (!restoreFromMemory && restoreFromFile) {
            // init state
            RecordOffset curr = readOffsetFromFile();
            if (curr != null) {
                long restoredPreOffset = curr.rOffset;
                String savedPreValue = curr.rValue;

                // if (file is truncated / switched to a new file handle) refused to set the restored value
                if (fileChannel.size() < restoredPreOffset || savedPreValue.isEmpty()) {
                    // fall through
                } else {
                    // try peek and check corresponding value at the restored offset
                    fileChannel.position(restoredPreOffset);
                    String peekValue = raf.readLine();
                    // restore confirms right, otherwise fall through
                    if (peekValue.equals(savedPreValue)) {
                        previousOffset = restoredPreOffset;
                        offset = fileChannel.position();
                        return;
                    }
                }
            }
        }

        if (startFromOption == LogTrackerStrategy.START_FROM_CURRENT) {
            try {
                offset = fileChannel.size();
            } catch (IOException e) {
                offset = 0;
            }
        } else {
            offset = 0;
        }

        fileChannel.position(offset);
    }

    private void logIfAllowed() {
        rollingLogCounter++;
        if (allowTracking && (TrackLatch <= rollingLogCounter)) {
            OutputStream os = null;
            try {
                os = new FileOutputStream(trackingFile);
                String output = Long.valueOf(previousOffset).toString() + "\n" + offsetValue;
                os.write(output.getBytes());
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                logger.error("Fail to write offset to tracking file.", e);
            } finally {
                if (os != null)
                    try {
                        os.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
            }
            rollingLogCounter = 0;
        }
    }

    private RecordOffset readOffsetFromFile() {
        if (allowTracking) {
            if (!trackingFile.exists()) return null;
            Scanner scanner = null;
            try {
                scanner = new Scanner(trackingFile);
                RecordOffset result = new RecordOffset();
                if (scanner.hasNextLine())
                    result.rOffset = Integer.parseInt(scanner.nextLine());
                if (scanner.hasNextLine())
                    result.rValue = scanner.nextLine();
                return result;
            } catch (FileNotFoundException e) {
                return null;
            } finally {
                if (scanner != null)
                    scanner.close();
            }
        } else {
            return null;
        }
    }

    private class RecordOffset {
        long rOffset = -1;
        String rValue = "";
    }
}