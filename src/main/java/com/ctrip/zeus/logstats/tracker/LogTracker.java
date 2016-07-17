package com.ctrip.zeus.logstats.tracker;

import com.ctrip.zeus.logstats.StatsDelegate;

import java.io.IOException;

/**
 * Created by mengyizhou on 10/18/15.
 */
public interface LogTracker {

    String getName();

    String getLogFilename();

    LogTrackerStrategy getStrategy();

    /**
     * This method check if the reading cursor has reached the end of the file at the time
     * when it is called. It might return a wrong value if file keeps growing.
     * This method is recommended to use iff reading a read-only file.
     * @return true if file cursor has reached the end of the file.
     * @throws java.io.IOException
     */
    boolean reachFileEnd() throws IOException;

    boolean reopenOnFileChange(String event);

    void start() throws IOException;

    void stop() throws IOException;

    String move() throws IOException;

    void fastMove(StatsDelegate<String> delegate) throws IOException;

    String LOGROTATE_COPYTRUNCATE = "copytruncate";
    String LOGROTATE_RENAME = "rename";
}