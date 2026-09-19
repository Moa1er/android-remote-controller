package com.moa1er.androidremotecontroller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SessionGuardTest {
    @Test
    public void invalidatesDelayedCallbacksWhenAConnectionChanges() {
        SessionGuard guard = new SessionGuard();
        long first = guard.start();
        long second = guard.start();

        assertFalse(guard.isCurrent(first));
        assertTrue(guard.isCurrent(second));

        guard.invalidate();
        assertFalse(guard.isCurrent(second));
    }
}
