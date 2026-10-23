package com.genymobile.scrcpy;

import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ServerTest {
    @Test
    public void testShutdownWatchdog() throws InterruptedException {
        CountDownLatch timedOut = new CountDownLatch(1);
        Thread watchdog = Server.startShutdownWatchdog(timedOut::countDown);

        // Normal process exit must not wait for the watchdog.
        Assert.assertTrue(watchdog.isDaemon());
        Assert.assertFalse(timedOut.await(100, TimeUnit.MILLISECONDS));
        Assert.assertTrue(timedOut.await(10, TimeUnit.SECONDS));
        watchdog.join();
    }
}
